/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.jdbc.testkit.internal

import java.sql.Statement

import scala.concurrent.Future

import akka.Done
import akka.actor.ClassicActorSystemProvider
import akka.annotation.InternalApi
import akka.dispatch.Dispatchers
import akka.persistence.jdbc.db.SlickDatabase
import akka.persistence.jdbc.db.SlickExtension
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import slick.jdbc.H2Profile
import slick.jdbc.JdbcBackend.Database
import slick.jdbc.JdbcProfile
import slick.jdbc.MySQLProfile
import slick.jdbc.OracleProfile
import slick.jdbc.PostgresProfile
import slick.jdbc.SQLServerProfile

/**
 * INTERNAL API
 */
@InternalApi
private[jdbc] object SchemaUtilsImpl {

  private val logger = LoggerFactory.getLogger("akka.persistence.jdbc.testkit.internal.SchemaUtilsImpl")

  /**
   * INTERNAL API
   */
  @InternalApi
  private[jdbc] def dropIfExists(logger: Logger)(implicit actorSystem: ClassicActorSystemProvider): Future[Done] = {

    val slickDb: SlickDatabase = loadSlickDatabase("jdbc-journal")
    val (fileToLoad, separator) = dropScriptFor(slickProfileToSchemaType(slickDb.profile))

    val blockingEC = actorSystem.classicSystem.dispatchers.lookup(Dispatchers.DefaultBlockingDispatcherId)
    Future(applyScriptWithSlick(fromClasspathAsString(fileToLoad), separator, logger, slickDb.database))(blockingEC)
  }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[jdbc] def createIfNotExists(logger: Logger)(
      implicit actorSystem: ClassicActorSystemProvider): Future[Done] = {

    val slickDb: SlickDatabase = loadSlickDatabase("jdbc-journal")
    val (fileToLoad, separator) = createScriptFor(slickProfileToSchemaType(slickDb.profile))

    val blockingEC = actorSystem.classicSystem.dispatchers.lookup(Dispatchers.DefaultBlockingDispatcherId)
    Future(applyScriptWithSlick(fromClasspathAsString(fileToLoad), separator, logger, slickDb.database))(blockingEC)
  }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[jdbc] def applyScript(script: String, separator: String, configKey: String, logger: Logger)(
      implicit actorSystem: ClassicActorSystemProvider): Future[Done] = {

    val blockingEC = actorSystem.classicSystem.dispatchers.lookup(Dispatchers.DefaultBlockingDispatcherId)
    Future(applyScriptWithSlick(script, separator, logger, loadSlickDatabase(configKey).database))(blockingEC)
  }

  /**
   * INTERNAL API
   *
   * This method runs the passed script against the Slick database.
   * This is a block operation.
   */
  @InternalApi
  private[jdbc] def applyScriptWithSlick(
      script: String,
      separator: String,
      logger: Logger,
      database: Database): Done = {

    def withStatement(f: Statement => Unit): Done = {
      val session = database.createSession()
      try session.withStatement()(f)
      finally session.close()
      Done
    }

    withStatement { stmt =>
      val lines = script.split(separator).map(_.trim)
      for {
        line <- lines if line.nonEmpty
      } yield {
        logger.debug(s"applying DDL: $line")

        try stmt.executeUpdate(line)
        catch {
          case t: java.sql.SQLException =>
            logger.debug(s"Exception while applying SQL script", t)
        }
      }
    }
  }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[jdbc] def dropScriptFor(schemaType: SchemaType): (String, String) =
    schemaType match {
      case Postgres  => ("schema/postgres/postgres-drop-schema.sql", ";")
      case MySQL     => ("schema/mysql/mysql-drop-schema.sql", ";")
      case Oracle    => ("schema/oracle/oracle-drop-schema.sql", "/")
      case SqlServer => ("schema/sqlserver/sqlserver-drop-schema.sql", ";")
      case H2        => ("schema/h2/h2-drop-schema.sql", ";")
    }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[jdbc] def createScriptFor(schemaType: SchemaType): (String, String) =
    schemaType match {
      case Postgres  => ("schema/postgres/postgres-create-schema.sql", ";")
      case MySQL     => ("schema/mysql/mysql-create-schema.sql", ";")
      case Oracle    => ("schema/oracle/oracle-create-schema.sql", "/")
      case SqlServer => ("schema/sqlserver/sqlserver-create-schema.sql", ";")
      case H2        => ("schema/h2/h2-create-schema.sql", ";")
    }

  private def slickProfileToSchemaType(profile: JdbcProfile): SchemaType =
    profile match {
      case PostgresProfile  => Postgres
      case MySQLProfile     => MySQL
      case OracleProfile    => Oracle
      case SQLServerProfile => SqlServer
      case H2Profile        => H2
    }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[jdbc] def fromClasspathAsString(fileName: String): String = {
    val is = getClass.getClassLoader.getResourceAsStream(fileName)
    io.Source.fromInputStream(is).mkString
  }

  private def loadSlickDatabase(configKey: String)(implicit actorSystem: ClassicActorSystemProvider) = {
    val journalConfig = actorSystem.classicSystem.settings.config.getConfig(configKey)
    SlickExtension(actorSystem).database(journalConfig)
  }

}
