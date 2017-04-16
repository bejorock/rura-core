package xyz.rura.labs.util

import scala.util.control.NonFatal
import java.lang.reflect.Constructor
import scala.collection.immutable
import java.lang.reflect.Type
import scala.annotation.tailrec
import java.lang.reflect.ParameterizedType
import scala.util.Try

import akka.util.BoxedType

case class ClassProps[T](_class:Class[_ <: T], args:Any*) extends Serializable
{
	def get():T = {
		if(_class == null) {
			return args.toList.head.asInstanceOf[T]
		} else {
			return args.toArray.length match {
				case 0 => _class.getConstructor().newInstance()
				case _ => ClassProps.findConstructor(_class, args.toList).newInstance(args.map{a => a.asInstanceOf[AnyRef]}.toArray:_*)
			}
		}
	}
}

object ClassProps
{
	def apply[T](t:T):ClassProps[T] = ClassProps(null, t)

	def findConstructor[T](clazz: Class[T], args: immutable.Seq[Any]): Constructor[T] = {
		def error(msg: String): Nothing = {
			val argClasses = args map safeGetClass mkString ", "
			throw new IllegalArgumentException(s"$msg found on $clazz for arguments [$argClasses]")
		}

		val constructor: Constructor[T] =
			if (args.isEmpty) Try { clazz.getDeclaredConstructor() } getOrElse (null)
			else {
				val length = args.length
				val candidates =
					clazz.getDeclaredConstructors.asInstanceOf[Array[Constructor[T]]].iterator filter { c ⇒
						val parameterTypes = c.getParameterTypes
						parameterTypes.length == length &&
							(parameterTypes.iterator zip args.iterator forall {
								case (found, required) ⇒
									found.isInstance(required) || BoxedType(found).isInstance(required) ||
										(required == null && !found.isPrimitive)
							})
					}
				if (candidates.hasNext) {
					val cstrtr = candidates.next()
					if (candidates.hasNext) error("multiple matching constructors")
					else cstrtr
				} else null
			}

		if (constructor == null) error("no matching constructor")
		else constructor
	}

	def safeGetClass(a: Any): Class[_] = if (a == null) classOf[AnyRef] else a.getClass
}