package xyz.rura.labs.core

import java.util.Date

/**
  * Created by ranalubis on 1/17/17.
  */
object Message {

    case class WebChanges(host:String, url:String, date:Date)

}
