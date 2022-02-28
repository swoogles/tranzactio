package io.github.gaelrenoux.tranzactio

import io.github.gaelrenoux.tranzactio.test.DatabaseModuleTestOps
import zio.{Tag, ZIO, ZLayer}

import java.sql.{Connection => JdbcConnection}
import zio.ZIO.attemptBlocking
import zio.ZEnvironment


/** TranzactIO module for Anorm. Note that the 'Connection' also includes the Blocking module, as tzio also needs to
 * provide the wrapper around the synchronous Anorm method. */
package object anorm extends Wrapper {
  override final type Connection = JdbcConnection
  override final type Database = Database.Service
  override final type Query[A] = JdbcConnection => A
  override final type TranzactIO[A] = ZIO[Connection, DbException, A]

  private[tranzactio] val connectionTag = implicitly[Tag[Connection]]

  override final def tzio[A](q: Query[A]): TranzactIO[A] =
    ZIO.environmentWithZIO[Connection] { c =>
      attemptBlocking(q(c.get[JdbcConnection]))
    }.mapError(DbException.Wrapped)

  /** Database for the Anorm wrapper */
  object Database
    extends DatabaseModuleBase[Connection, DatabaseOps.ServiceOps[Connection]]
      with DatabaseModuleTestOps[Connection] {

    self =>

    private[tranzactio] override implicit val connectionTag: Tag[Connection] = anorm.connectionTag

    /** How to provide a Connection for the module, given a JDBC connection and some environment. */
    override final def connectionFromJdbc(env: TranzactioEnv, connection: JdbcConnection): ZIO[Any, Nothing, Connection] = {
      // TODO: what to do with env (clock)
      // Tempt to think its not needed
      ZIO.succeed(connection)
    } 

   
    /** Creates a Database Layer which requires an existing ConnectionSource. */
    override final def fromConnectionSource: ZLayer[ConnectionSource, Nothing, Database] =  ???
      // ZLayer.fromFunction { env: ZEnvironment[ConnectionSource] =>
        
      //   new DatabaseServiceBase[Connection](env.get[ConnectionSource.Service]) with Database.Service {
      //     override final def connectionFromJdbc(connection: JdbcConnection): ZIO[Any, Nothing, Connection] = self.connectionFromJdbc(connection)
      //   }
      // }



  }


}
