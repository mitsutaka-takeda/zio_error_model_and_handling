# はじめに

この記事ではZIOのエラー・モデルとエラー処理について紹介します。

# エラー・モデル

ZIOは実行の失敗(=エラー)を`Cause[E]`という代数的データ型で表現します。`Cause[E]`の型パラメータ`E`は`ZIO[R, E, A]`の2番目の型パラメータ`E`と
同じ型でアプリケーション・ロジックに関する失敗を表現する型です。`Cause[E]`は概念的にはアプリケーション内の失敗`E`とアプリケーション外の失敗`Throwable`の直和型です。

例えばユーザ管理を行っているサービスがあります。指定したユーザ`userId`のアイコンを更新するロジック`updateUserIcon`は以下のように記述できます。
指定されたユーザが見つからずアイコンの更新が失敗する可能性をロジックのシグネチャで`ZIO[R, E, A]`の`E`に`UserNotFound.type`型を指定して表現します。
対応する`Cause[E]`は`Cause[UserNotFound.type]`になります。”ユーザが見つからない”といったアプリケーション上の制約以外にもOut of Memory、
スレッドの中断などが原因で`updateUserIcon`は失敗する可能性があります。

```scala
final case class UserId()
final case class Icon()

object UserNotFound

object UserService {
  def updateUserIcon(userId: UserId, icon: Icon): scalaz.zio.ZIO[Any, UserNotFound.type, Unit] = ???
}
```

# エラーへのアクセス＆エラー処理

エラーにアクセスして処理する方法を紹介します。

## アプリケーション・エラー(`E`)

`ZIO#fold`メソッドを使用するとアプリケーション・ロジックの結果(`ZIO[R, E, A]`の`R`)とアプリケーション・ロジックの失敗(`ZIO[R, E, A]`の`E`)に同時にアクセスすることができます。

先ほどの`UserService`を利用してREST APIのエンドポイントを提供する`UserController`サービスを例とします。`UserController`は`UserService`の結果、またはエラーをレスポンスに変換してクライアントに返します。
`UserController#endpointForUpdatingUserIcon`のシグネチャ`ZIO[Any, Nothing, Response]`でエラー情報の部分が`Nothing`になりました。
これは失敗`UserNotFound.type`をアプリケーション上処理して、結果`Response`に変換したためです。`UserController`のクライアントは存在しないユーザのアイコンを更新しようとしても処理は失敗せず、処理の結果`Response`を受け取ることができます。

```scala
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
```

`ZIO.mapError`を使用するとエラーにのみアクセスすることができます。Effective Javaで紹介されているerror translationなどのイディオムを実装するときに便利です。

前回までの例と同じ機能をClean Architectureで実装する例を考えます。Clean Architectureではインフラ側の失敗(`Throwable`)をラップしてアプリケーションの失敗として扱います。
ユースケースのロジック`UpdateUserIconUseCase#execute`は`ZIO[IUserRepository, UseCaseError, Unit]`を返しています。アプリケーション・レベルの失敗(`UserNotFound`)と
インフラの失敗(`Throwable`)をerror translation(`mapError(InfraError)`)によって同じアプリケーション・レベルの失敗(`UseCaseError`)として扱います。

```scala
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
```

## アプリケーション外のエラー(`Throwable`)

アプリケーション外の失敗はロジックの型情報(`ZIO[R, E, A]`)には現れません。アクセスするにはをアプリケーション・ロジックの結果(`ZIO[R, E, A]`の`A`)やアプリケーション・ロジックの失敗(`ZIO[R, E, A]`の`E`)として取り出す必要があります。

`ZIO#sandbox`メソッドを利用するとアプリケーション外の失敗情報へアクセスできるようになります。
型`ZIO[R, E, A]`のロジックに対して`sandbox`を呼び出すとロジックの型は`ZIO[R, Cause[E], A]`になり、前述のとおり`Cause[E]`がアプリケーションの失敗(`Failure[E]`)と
アプリケーション外の失敗`Throwable`を含む代数的データ型です。

最初の`UserService`の例でSandbox化の前後で型を比べてみます。`ZIO[Any, UserNotFound.type, Unit]`のロジックをSandbox化すると
`ZIO[Any, Exit.Cause[UserNotFound.type], Unit] `になります。

```scala
import scalaz.zio.{Exit, ZIO}

final case class UserId()
final case class Icon()

object UserNotFound

object UserService {
  def updateUserIcon(userId: UserId, icon: Icon)         : scalaz.zio.ZIO[Any, UserNotFound.type                       , Unit] = ???
  def updateUserIconSandboxed(userId: UserId, icon: Icon): scalaz.zio.ZIO[Any, scalaz.zio.Exit.Cause[UserNotFound.type], Unit] 
    = updateUserIcon(userId, icon)
      .sandbox
}
```

Sandbox化で`Cause[E]`を取り出した後は前述の`fold`や`mapError`で扱うことができます。先ほどと同じ`Controller`の例でアプリケーション外の失敗も`Response`で返すように修正します。
`Cause[E]#failureOrCause`メソッドで`Either`型に変換することができます。
`Left`がアプリケーション内の失敗`E`で`Right`がアプリケーション外の失敗`Cause[Nothing]`です。

`Right`が`Throwable`ではなく`Cause[Nothing]`であるのは、`Cause[E]`は複数の失敗を保持できるように設計されているためです。
複数の失敗情報のうち"最も重要な失敗"を`squash`で取得します。`squash`の実装では"アプリケーション内の失敗`E` > `InterruptedException` >
その他の`Throwable`"の順に重要度が定義されています。例えば、`E`と`InterruptedException`の両方が発生した場合、`squash`の結果は`E`になります。

```scala
final case class UserId()
final case class Icon()

object UserNotFound

object UserService {
  def updateUserIcon(userId: UserId, icon: Icon): scalaz.zio.ZIO[Any, UserNotFound.type, Unit] = ???
}

final case class Request(userId: UserId, icon: Icon)
final case class Response()

object UserController {
  def throwableToResponse(th: Throwable): Response = ???
  def errorToResponse(notFound: UserNotFound.type): Response = ???
  def successToResponse(unit: Unit): Response = ???

  def endpointForUpdatingUserIcon(request: Request): scalaz.zio.ZIO[Any, Nothing, Response] = for {
    response <- UserService.updateUserIcon(request.userId, request.icon)
      .sandbox
      .fold(c =>
        c.failureOrCause match {
          case Left(value)  => // value: UserNotFound.type
            errorToResponse(value)
          case Right(value) => // value: Exit.Cause[Nothing]
            throwableToResponse(value.squash)
        },
        successToResponse
      )
  } yield response
}
```

# ベストプラクティス（エラー型の定義方法）

[Error Management: Future vs ZIO:スライド](https://www.slideshare.net/jdegoes/error-management-future-vs-zio)で紹介されているZIOのエラー処理のベストプラクティスのうちの１つ紹介します。

エラー型を定義するときは`Exception`を継承した`seald trait`を使用するようにしましょう。`ZIO[R, E, A]`は`E`型に対してcovariantで設計されているため複数のエラーを共通の型へ自動的に拡張してくれます。

以下サービスごとにエラーを2系統(`UserServiceError`と`NotificationServiceError`)定義したケースです。2つのサービスを利用した`logic`では共通の`ApplicationError`に拡張されます。
このエラー拡張では`Nothing`も意図通りに動作します。ログはアプリケーションのロジックに影響を与えるべきではないため`LoggineService#log`は"アプリケーションレベルでは失敗しません"(`E = Nothing`)。
ロジック中にログを取得しても失敗の型は`ApplicationError`です。

このようにエラー型の拡張は自動で行われるためエラーは不必要に抽象的な型を返さないようにしましょう。例えば`UserService`が`ApplicationError`を返したり、
`LoggingService`が`ApplicationError`を返すことはやめましょう。

```scala
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
```

# Clean Architecture再訪

最後にZIOのエラー・モデルを利用してClean Architectureのエラー・モデルを単純化する方法を見てみたいと思います。
前述のとおり`Cause[E]`は実質的にはアプリケーション内の失敗`E`とアプリケーション外の失敗`Throwable`の直和でした。
アプリケーション外の失敗を`E`でラップすることなく`Cause[E]`で表現することができます。

先ほどのClean Architectureのコードからインフラ側の失敗をラップする`InfraError`を削除します。error translation(`mapError`)をしていた箇所で`ZIO#orDie`を呼び出します。
`ZIO#orDie`はアプリケーション内の失敗からアプリケーション外の失敗へと変換します。`ZIO[R, E, A]`に対して`orDie`を呼び出すと`ZIO[R, Nothing, A]`という型になります。
重要なことはエラー情報を伝えるチャネルが`E`から`Cause[E]`に変わるだけでエラー情報は失われないということです。
`InfraError`を削除する前のコードと削除した後のコードは等価です。さらに等価の変換を推し進めて`IUserRepository#find`や`IUserRepository#store`の型をそれぞれ
`ZIO[Any, Nothing, Option[User]]`と`ZIO[Any, Nothing, Unit]`に変更すれば`orDie`の呼び出しも不要になります。

```scala
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
case object UserNotFound extends UseCaseError

object UpdateUserIconUseCase {
  def execute(id: UserId, icon: Icon): scalaz.zio.ZIO[IUserRepository, UseCaseError, Unit] = for {
    repository <- scalaz.zio.ZIO.environment[IUserRepository]
    maybeUser <- repository.find(id).orDie
    _ <- maybeUser match {
      case Some(user) =>
        repository.store(user).orDie
      case None =>
        scalaz.zio.ZIO.fail(UserNotFound)
    }
  } yield ()
}
```

# 最後に

この記事ではZIOではエラー・モデル、エラー情報へのアクセス方法、ベストプラクティスを紹介しました。

ZIOでは失敗を大きく2つに分類します。アプリケーション内の失敗とアプリケーション外の失敗です。アプリケーション内の失敗は副作用`ZIO[R, E, A]`の`E`という型で表現されます。
アプリケーション外の失敗は`Throwable`で表現され副作用の型にはでてきません。また`Cause[E]`という型でアプリケーション内と外の失敗の直和を表現します。

アプリケーション内の失敗`E`にアクセスするには`fold`や`mapError`を使用します。

アプリケーション外の失敗`Throwable`にアクセスするには`sandbox`で`Cause[E]`を取得する必要があります。

ZIOのエラーの仕組みを最大限に活用するには、エラーを`Exception`から派生させた`sealed trait`（直和型）で表現しましょう。
ロジックで必要な最低限のエラーを返すようにするとcovariantを活かして合成が楽になります。

またこの記事では紹介しませんでしたが、ZIOには強力なトレース機能が備わっています。
興味のある人には[Error Management: Future vs ZIO:動画](https://www.youtube.com/watch?v=mGxcaQs3JWI)をお勧めします。

# 参考

- [Effective Java](http://www.informit.com/store/effective-java-9780134685991)
- [Error Management: Future vs ZIO:スライド](https://www.slideshare.net/jdegoes/error-management-future-vs-zio)
- [Error Management: Future vs ZIO:動画](https://www.youtube.com/watch?v=mGxcaQs3JWI)
- [コード](https://github.com/mitsutaka-takeda/zio_error_model_and_handling.git)