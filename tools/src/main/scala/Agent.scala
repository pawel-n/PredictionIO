package io.prediction.tools.agent

import io.prediction.controller.Utils
import io.prediction.core.BuildInfo
import io.prediction.tools.ConsoleArgs

import grizzled.slf4j.Logging
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json4s.JsonDSL._
import org.json4s.native.JsonMethods._

import scala.sys.process._

import java.net.URI
import javax.net.ssl.SSLContext

class AgentWebSocketClient(uri: URI, pioHome: String)
    extends WebSocketClient(uri) with Logging {
  implicit val formats = Utils.json4sDefaultFormats

  override def onOpen(hs: ServerHandshake): Unit = {
    info(s"Connected to ${uri.toASCIIString}")
    val status = Agent.pioStatus(pioHome)
    info(status)
    send(status)
  }

  override def onMessage(msg: String): Unit = {
    val json = try {
      parse(msg)
    } catch {
      case e: Throwable =>
        error(e.getMessage)
        return
    }
    val event = (json \ "event").extractOpt[String]
    val action = (json \ "action").extractOpt[String]
    (event, action) match {
      case (Some("client:status"), Some("get")) =>
        val status = Agent.pioStatus(pioHome)
        info(status)
        send(status)
      case (Some("client:ping"), Some("get")) =>
        val json =
          ("event" -> "client:status") ~
          ("action" -> "set") ~
          ("version" -> BuildInfo.version) ~
          ("data" -> "pong")
        send(compact(render(json)))
      case _ =>
        error(s"Unknown message received: ${event} ${action}")
    }
  }

  override def onClose(code: Int, reason: String, remote: Boolean): Unit = {
    info("Disconnected")
  }

  override def onError(e: Exception): Unit = {
    e.printStackTrace
  }
}

object Agent extends Logging {
  def pioStatus(piohome: String) = {
    val status =
      Process(s"${piohome}/bin/pio status --json").lines_!.toList.last
    try {
      val statusJson = parse(status)
      val json =
        ("event" -> "client:status") ~
        ("action" -> "set") ~
        ("version" -> BuildInfo.version) ~
        ("data" -> statusJson)
      compact(render(json))
    } catch {
      case e: Throwable => e.getMessage
    }
  }

  def start(ca: ConsoleArgs): Int = {
    val url = sys.env.get("PIO_AGENT_URL").getOrElse {
      ca.common.agentUrl.getOrElse {
        error("URL not found from configuration file nor command line. Aborting.")
        return 1
      }
    }

    val hostname = sys.env.get("PIO_AGENT_HOSTNAME").getOrElse {
      ca.common.agentHostname.getOrElse {
        error("Hostname not found from configuration file nor command line. Aborting.")
        return 1
      }
    }

    val secret = sys.env.get("PIO_AGENT_SECRETKEY").getOrElse {
      ca.common.agentSecret.getOrElse {
        error("Secret key not found from configuration file nor command line. Aborting.")
        return 1
      }
    }

    val uri = new URI(
      s"${url}/api/socket?hostname=${hostname}&secret_key=${secret}")
    val agent = new AgentWebSocketClient(uri, ca.common.pioHome.get)
    if (uri.getScheme == "wss") {
      val sslContext = SSLContext.getInstance("TLS")
      sslContext.init(null, null, null)
      val factory = sslContext.getSocketFactory
      agent.setSocket(factory.createSocket)
    }
    agent.connectBlocking

    while (!agent.isClosed)
      Thread.sleep(1000)

    0
  }
}