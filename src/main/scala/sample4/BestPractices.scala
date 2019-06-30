package sample4

sealed trait ApplicationError extends Exception
sealed trait UserServiceError extends ApplicationError
case object UserNotFound extends UserServiceError
sealed trait NotificationServiceError extends ApplicationError
case object TemporaryUnavailable extends NotificationServiceError

final case class UserId()
final case class User(email: Email)
final case class Email()

object UserService {
  def getUserInfo(userId: UserId): scalaz.zio.ZIO[Any, UserNotFound.type, User] = ???
}

object NotificationService {
  def sendEmail(email: Email): scalaz.zio.ZIO[Any, TemporaryUnavailable.type, Unit] = ???
}

object LoggingService {
  def log(msg: String): scalaz.zio.ZIO[Any, Nothing, Unit] = ???
}

object Application{
  val logic: scalaz.zio.ZIO[Any, ApplicationError, Unit] = for {
    u <- UserService.getUserInfo(UserId())
    _ <- NotificationService.sendEmail(u.email)
    _ <- LoggingService.log("successful!")
  } yield ()
}