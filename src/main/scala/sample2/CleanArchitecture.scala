package sample2

final case class UserId()
final case class Icon()
final case class User() {
  def update(icon: Icon): User = ???
}

trait IUserRepository {
  def find(id: UserId): scalaz.zio.ZIO[Any, Throwable, Option[User]]
  def store(user: User): scalaz.zio.ZIO[Any, Throwable, Unit]
}

sealed trait UseCaseError
final case class InfraError(cause: Throwable) extends UseCaseError
case object UserNotFound extends UseCaseError

object UpdateUserIconUseCase {
  def execute(id: UserId, icon: Icon): scalaz.zio.ZIO[IUserRepository, UseCaseError, Unit] = for {
    repository <- scalaz.zio.ZIO.environment[IUserRepository]
    maybeUser <- repository.find(id).mapError(InfraError)
    _ <- maybeUser match {
      case Some(user) =>
        repository.store(user).mapError(InfraError)
      case None =>
        scalaz.zio.ZIO.fail(UserNotFound)
    }
  } yield ()
}
