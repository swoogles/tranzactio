package io.github.gaelrenoux.tranzactio

import zio._

import zio.Clock

import java.sql.Connection
import javax.sql.DataSource
import zio.ZIO.attemptBlocking

/** A module able to provide and manage connections. They typically come from a connection pool. */
object ConnectionSource {

  trait Service {

    def runTransaction[R, E, A](task: Connection => ZIO[R, E, A], commitOnFailure: Boolean = false)
      (implicit errorStrategies: ErrorStrategiesRef): ZIO[R, Either[DbException, E], A]

    def runAutoCommit[R, E, A](task: Connection => ZIO[R, E, A])
      (implicit errorStrategies: ErrorStrategiesRef): ZIO[R, Either[DbException, E], A]
  }

  /** ConnectionSource with standard behavior. Children class need to implement `getConnection`. */
  abstract class ServiceBase(
      env: Any with Clock,
      val defaultErrorStrategies: ErrorStrategiesRef
  ) extends ConnectionSource.Service {

    /** Main function: how to obtain a connection. Needs to be provided. */
    protected def getConnection: RIO[Any, Connection]

    def runTransaction[R, E, A](task: Connection => ZIO[R, E, A], commitOnFailure: Boolean = false)
      (implicit errorStrategies: ErrorStrategiesRef): ZIO[R, Either[DbException, E], A] =
      openConnection.mapError(Left(_)).acquireReleaseWith(closeConnection(_).orDie) { c: Connection =>
        setAutoCommit(c, autoCommit = false)
          .mapError(Left(_))
          .zipRight {
            task(c).mapError(Right(_))
          }
          .tapBoth(
            _ => if (commitOnFailure) commitConnection(c).mapError(Left(_)) else rollbackConnection(c).mapError(Left(_)),
            _ => commitConnection(c).mapError(Left(_))
          )
      }

    def runAutoCommit[R, E, A](task: Connection => ZIO[R, E, A])
      (implicit errorStrategies: ErrorStrategiesRef): ZIO[R, Either[DbException, E], A] =
      openConnection.mapError(Left(_)).acquireReleaseWith(closeConnection(_).orDie) { c: Connection =>
        setAutoCommit(c, autoCommit = true)
          .mapError(Left(_))
          .zipRight {
            task(c).mapError(Right(_))
          }
      }

    // TODO handle error reporting when retrying

    private def bottomErrorStrategy(implicit errorStrategies: ErrorStrategiesRef) =
      errorStrategies.orElse(defaultErrorStrategies).orElseDefault

    def openConnection(implicit errorStrategies: ErrorStrategiesRef): ZIO[Any, DbException, Connection] =
      wrap(bottomErrorStrategy.openConnection) {
        getConnection.mapError(e => DbException.Wrapped(e))
      }

    def setAutoCommit(c: Connection, autoCommit: Boolean)(implicit errorStrategies: ErrorStrategiesRef): ZIO[Any, DbException, Unit] =
      wrap(bottomErrorStrategy.setAutoCommit) {
        attemptBlocking(c.setAutoCommit(autoCommit))
      }

    def commitConnection(c: Connection)(implicit errorStrategies: ErrorStrategiesRef): ZIO[Any, DbException, Unit] =
      wrap(bottomErrorStrategy.commitConnection) {
        attemptBlocking(c.commit())
      }

    def rollbackConnection(c: Connection)(implicit errorStrategies: ErrorStrategiesRef): ZIO[Any, DbException, Unit] =
      wrap(bottomErrorStrategy.rollbackConnection) {
        attemptBlocking(c.rollback())
      }

    /** Cannot fail */
    def closeConnection(c: Connection)(implicit errorStrategies: ErrorStrategiesRef): ZIO[Any, DbException, Unit] =
      wrap(bottomErrorStrategy.closeConnection) {
        attemptBlocking(c.close())
      }

    private def wrap[R, A](es: ErrorStrategy)(z: ZIO[Any, Throwable, A]) = es {
      z.mapError(e => DbException.Wrapped(e))
    }.provide(env)

  }

  /** Service based on a DataSource. */
  private class DatasourceService(
      env: DataSource with Any with Clock,
      defaultErrorStrategies: ErrorStrategiesRef
  ) extends ServiceBase(env, defaultErrorStrategies) {
    private val ds = env.get[DataSource]

    override def getConnection: RIO[Any, Connection] = attemptBlocking {
      ds.getConnection()
    }
  }

  /** Service based on a single connection, which is reused each time. Uses a Semaphore to make sure the connection
   * can't be used by concurrent operations. */
  private class SingleConnectionService(
      connection: Connection,
      semaphore: Semaphore,
      env: Any with Clock,
      defaultErrorStrategies: ErrorStrategiesRef
  ) extends ServiceBase(env, defaultErrorStrategies) {

    override def getConnection: UIO[Connection] = UIO.succeed(connection)

    override def closeConnection(c: Connection)(implicit errorStrategies: ErrorStrategiesRef): ZIO[Any, Nothing, Unit] = ZIO.unit

    override def runTransaction[R, E, A](task: Connection => ZIO[R, E, A], commitOnFailure: Boolean)
      (implicit errorStrategies: ErrorStrategiesRef): ZIO[R, Either[DbException, E], A] =
      semaphore.withPermit {
        super.runTransaction(task, commitOnFailure)
      }

    override def runAutoCommit[R, E, A](task: Connection => ZIO[R, E, A])
      (implicit errorStrategies: ErrorStrategiesRef): ZIO[R, Either[DbException, E], A] =
      semaphore.withPermit {
        super.runAutoCommit(task)
      }
  }

  val any: ZLayer[DataSource, Nothing, DataSource] = ZLayer.requires[DataSource]

  /** ConnectionSource created from a DataSource. Any connection pool you use should be able to provide a DataSource.
   *
   * When a Database method is called with no available implicit ErrorStrategiesRef, the default ErrorStrategiesRef will
   * be used. */
  val fromDatasource: ZLayer[DataSource with TranzactioEnv, Nothing, ConnectionSource] =
    fromDatasource(ErrorStrategies.Parent)

  /** As `fromDatasource`, but provides a default ErrorStrategiesRef.
   *
   * When a Database method is called with no available implicit ErrorStrategiesRef, the ErrorStrategiesRef in argument
   * will be used. */
  def fromDatasource(errorStrategies: ErrorStrategiesRef): ZLayer[DataSource with TranzactioEnv, Nothing, ConnectionSource] =
    ZIO.access[DataSource with TranzactioEnv] { env =>
      new DatasourceService(env, errorStrategies)
    }.toLayer

  /** As `fromDatasource(ErrorStrategiesRef)`, but an `ErrorStrategies` is provided through a layer instead of as a parameter. */
  val fromDatasourceAndErrorStrategies: ZLayer[DataSource with ErrorStrategies with TranzactioEnv, Nothing, ConnectionSource] =
    ZIO.access[DataSource with ErrorStrategies with TranzactioEnv] { env =>
      val errorStrategies = env.get[ErrorStrategies]
      new DatasourceService(env, errorStrategies)
    }.toLayer

  /** ConnectionSource created from a single connection. If several operations are launched concurrently, they will wait
   * for the connection to be available (see the Semaphore documentation for details).
   *
   * When a Database method is called with no available implicit ErrorStrategiesRef, the default ErrorStrategiesRef will
   * be used. */
  val fromConnection: ZLayer[Connection with Any with Clock, Nothing, ConnectionSource] =
    fromConnection(ErrorStrategies.Parent)

  /** As `fromConnection`, but provides a default ErrorStrategiesRef.
   *
   * When a Database method is called with no available implicit ErrorStrategiesRef, the ErrorStrategiesRef in argument
   * will be used. */
  def fromConnection(errorStrategiesRef: ErrorStrategiesRef): ZLayer[Connection with TranzactioEnv, Nothing, ConnectionSource] =
    ZIO.environmentWithZIO[Connection with TranzactioEnv] { env =>
      val connection = env.get[Connection]
      Semaphore.make(1).map {
        new SingleConnectionService(connection, _, env, errorStrategiesRef)
      }
    }.toLayer

}
