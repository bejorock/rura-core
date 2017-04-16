package xyz.rura.labs.io.monitor

import kamon.metric._
import kamon.metric.instrument.{ Time, InstrumentFactory }

class ReactiveClientMetrics(instrumentFactory: InstrumentFactory) extends GenericEntityRecorder(instrumentFactory) 
{
  val successTime = histogram("success-time", Time.Nanoseconds)
  val errorTime = histogram("error-time", Time.Nanoseconds)
  val errors = counter("errors")
}

object ReactiveClientMetrics extends EntityRecorderFactoryCompanion[ReactiveClientMetrics]("reactive-client", new ReactiveClientMetrics(_))