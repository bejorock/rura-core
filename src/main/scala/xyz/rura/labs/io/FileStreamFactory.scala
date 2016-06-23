package xyz.rura.labs.io

import java.io._
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.{ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY}

import akka.actor.ActorSystem
import akka.event.Logging

import scala.io.Source
import scala.collection.JavaConversions._
import scala.concurrent.Promise
import scala.concurrent.Future
import scala.concurrent.duration._

import org.apache.commons.io.IOUtils
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.WildcardFileFilter
import org.apache.commons.io.filefilter.DirectoryFileFilter
import org.apache.commons.io.filefilter.IOFileFilter
import org.apache.commons.io.filefilter.FileFileFilter

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

import xyz.rura.labs.util._

object FileStreamFactory
{
	def src(globs:Array[String])(implicit system:ActorSystem):ReactiveStream = {
		val files = FileUtils.listFiles(new File("."), new FileFileFilter() {
			val matchers = globs map{g => FileSystems.getDefault().getPathMatcher("glob:./" + g)}

			override def accept(file:File):Boolean = {
				return super.accept(file) && matchers.foldLeft(false){(result, m) => result | m.matches(file.toPath())}
			}
		}, DirectoryFileFilter.DIRECTORY)

		val vfs = new Iterable[VirtualFile]() {
			def iterator = files.iterator map{f => 
				val in = new FileInputStream(f)
				val vf = VirtualFile(f.getName(), f.getPath(), Some(VirtualFile.DEFAULT_ENCODING), IOUtils.toBufferedInputStream(in))

				in.close()

				vf
			}
		}

		// return new AsyncStream(Promise.successful(vfs.iterator).future)
		return new ReactiveStream(vfs)
	}

	def src(glob:String)(implicit system:ActorSystem):ReactiveStream = src(Array(glob))

	def watch(globs:Array[String], duration:Duration)(implicit system:ActorSystem):ReactiveStream = {
		import system.dispatcher

		// setup watcher
		val watcher = FileSystems.getDefault().newWatchService()

		val files = FileUtils.listFiles(new File("."), new FileFileFilter() {
			val matchers = globs map{g => FileSystems.getDefault().getPathMatcher("glob:./" + g)}

			override def accept(file:File):Boolean = {
				return super.accept(file) && matchers.foldLeft(false){(result, m) => result | m.matches(file.toPath())}
			}
		}, DirectoryFileFilter.DIRECTORY).toList

		val dirs = files.map{f => 
			if(f.isDirectory) {
				f
			} else {
				f.getParentFile
			}
		}.distinct

		// get paths
		val paths = dirs map{d => d.toPath}
		// register watcher
		paths foreach{p => p.register(watcher, ENTRY_MODIFY)}

		val input = new Iterable[VirtualFile]() {
			val log = Logging(system, this.getClass())

			def iterator = new Iterator[VirtualFile]() {
				var isNext = true
				var lastNext:VirtualFile = null

				val queue = new ArrayBlockingQueue[VirtualFile](100)
				val deadline = duration.isFinite match {
					case false => null
					case true => duration.asInstanceOf[FiniteDuration].fromNow
				}

				// start polling for changes
				Future {
					while(!isExpired) {
						// waiting for events
						val watchKey = watcher.poll(10, TimeUnit.SECONDS)
						
						if(watchKey != null) {
							val dirPath = watchKey.watchable.asInstanceOf[Path]

							// analyze events
							watchKey.pollEvents() foreach{event =>
								val path = event.context().asInstanceOf[Path]
								val file = dirPath.resolve(path).toFile

								// only tapping to modify event
								if(files.exists{f => (dirPath.resolve(path).compareTo(f.toPath) == 0)}) {
									queue.put(VirtualFile(file.getName(), file.getPath(), Some(VirtualFile.DEFAULT_ENCODING), new FileInputStream(file)))
								}
							}

							val valid = watchKey.reset()

							if(!valid) {
								log.info("key has been unregistered")
							}
						}
					}
				}

				def isExpired:Boolean = {
					if(!duration.isFinite) {
						return false
					} else {
						return deadline.isOverdue
					}
				}

				def hasNext:Boolean = {
					if(isExpired) {
						return false
					}

					if(lastNext != null) {
						return isNext
					}

					lastNext = duration.isFinite match {
						case true => queue.poll(5, TimeUnit.SECONDS)
						case false => queue.take()
					}

					if(lastNext == null) {
						return hasNext
					}

					isNext = !isExpired

					return isNext
				}

				def next:VirtualFile = {
					val tmp = lastNext
					lastNext = null

					return tmp
				}
			}
		}

		return new ReactiveStream(input)
	}

	def dest(dirName:String):ClassProps[Mapper] = ClassProps(classOf[FileStreamFactory.Dest], dirName)

	final class Dest(dirName:String) extends AbstractMapper {
		val dir = new File(dirName)

		def map(f:VirtualFile, callback:(VirtualFile, Exception) => Unit):Unit = {
			val file = new File(dirName, f.name)
			val out = new FileOutputStream(file)
			
			IOUtils.copy(f.inputstream, out)
			out.close()

			callback(f, null)
		}
	}
}