package coinffeine.gui.util

import java.net.URI
import scala.sys.process._
import scala.util.Try
import scalafx.scene.control.Alert
import scalafx.scene.control.Alert.AlertType

import com.typesafe.scalalogging.LazyLogging

import coinffeine.common.Platform

trait Browser {
  /** Use default desktop browser to get to the passed URI */
  def browse(location: URI): Unit
}

object Browser {

  private object AwtBrowser extends Browser {
    override def browse(location: URI): Unit = java.awt.Desktop.getDesktop.browse(location)
  }

  /** As AWT browser is broken in Linux, try to use XDG or other generic ways to open links */
  private object LinuxBrowser extends Browser with LazyLogging {

    override def browse(location: URI): Unit = {

      def tryBrowser(browser: String) = Try(Seq(browser, location.toString).run())

      def cannotFindBrowser(): Unit = {
        new Alert(AlertType.Error) {
          title = "Cannot open a browser"
          headerText = "Cannot find the default browser"
          contentText = location.toString
        }.showAndWait()
      }

      tryBrowser("xdg-open") orElse
        tryBrowser("sensible-browser") orElse
        tryBrowser("gnome-open") orElse
        tryBrowser("kde-open") getOrElse
        cannotFindBrowser()
    }
  }

  def forPlatform(platform: Platform): Browser = platform match {
    case Platform.Linux => LinuxBrowser
    case _ => AwtBrowser
  }

  lazy val default = forPlatform(Platform.detect())
}
