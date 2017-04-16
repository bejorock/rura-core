package xyz.rura.labs.util

import java.io._

object StreamTools
{
	def encode(o:Object):InputStream = {
		val out = new ByteArrayOutputStream()
		val writer = new ObjectOutputStream(out)

		// write object
		writer.writeObject(o)
		writer.flush()
		writer.close()

		return new ByteArrayInputStream(out.toByteArray)
	}

	def decode[T](in:InputStream):T = {
		val reader = new ObjectInputStream(in)
		val o = reader.readObject()

		reader.close()

		return o.asInstanceOf[T]
	}
}