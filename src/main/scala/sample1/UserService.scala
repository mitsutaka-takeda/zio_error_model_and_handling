package sample1

final case class UserId()
final case class Icon()

object UserNotFound

object UserService {
  def updateUserIcon(userId: UserId, icon: Icon): scalaz.zio.ZIO[Any, UserNotFound.type, Unit] = ???
}

final case class Request(userId: UserId, icon: Icon)
final case class Response()

object UserController {
  def errorToResponse(notFound: UserNotFound.type): Response = ???
  def successToResponse(unit: Unit): Response = ???

  def endpointForUpdatingUserIcon(request: Request): scalaz.zio.ZIO[Any, Nothing, Response] = for {
    response <- UserService.updateUserIcon(request.userId, request.icon)
      .fold(
        errorToResponse,
        successToResponse
      )
  } yield response
}
