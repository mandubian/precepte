package monitor

import scala.concurrent.Future
import scalaz.{ Hoist, Monad, Kleisli, OptionT, MonadTrans, ListT, EitherT, Applicative, Functor, ∨ }
import scalaz.Leibniz.{===, refl}
import scalaz.Id._
import scalaz.Unapply
import shapeless.{ HList, :: }

trait HasHoist[M[_]] {
  type T[_[_], _]
  def lift[F[_], A](f: F[M[A]]): T[F, A]
}

object HasHoist {
  type Aux[M[_], T0[_[_], _]] = HasHoist[M] { type T[F[_], A] = T0[F, A] }

  def apply[M[_]](implicit h: HasHoist[M]): Aux[M, h.T] = h

  implicit object optionHasHoist extends HasHoist[Option] {
    type T[F[_], A] = OptionT[F, A]
    def lift[F[_], A](f: F[Option[A]]): OptionT[F, A] = OptionT.apply(f)
  }

  implicit object listHasHoist extends HasHoist[List] {
    type T[F[_], A] = ListT[F, A]
    def lift[F[_], A](f: F[List[A]]): ListT[F, A] = ListT.apply(f)
  }

  private[this] class EitherHasHoist[A] extends HasHoist[({ type λ[α] = A ∨ α })#λ] {
    type T[F[_], B] = EitherT[F, A, B]
    def lift[F[_], B](f: F[A ∨ B]): EitherT[F, A, B] = EitherT.apply(f)
  }

  implicit def eitherHasHoist[A]: HasHoist.Aux[({ type λ[α] = A ∨ α })#λ, ({ type λ[F[_], B] = EitherT[F, A, B] })#λ] = new EitherHasHoist[A]
}

trait CoHasHoist[T[_]] {
  type F[_]
  type G[_]
  def unlift[A](f: T[A]): F[G[A]]
}

object CoHasHoist {
  type Aux[T[_], F0[_], G0[_]] = CoHasHoist[T] {
    type F[X] = F0[X]
    type G[X] = G0[X]
  }

  def apply[T0[_]](implicit ch: CoHasHoist[T0]): Aux[T0, ch.F, ch.G] = ch

  implicit def optionCoHasHoist[F0[_]] = new CoHasHoist[({ type λ[α] = OptionT[F0, α] })#λ] {
    type F[T] = F0[T]
    type G[T] = Option[T]
    def unlift[A](o: OptionT[F, A]): F[Option[A]] = o.run
  }

  implicit def listCoHasHoist[F0[_]] = new CoHasHoist[({ type λ[α] = ListT[F0, α] })#λ] {
    type F[T] = F0[T]
    type G[T] = List[T]
    def unlift[A](o: ListT[F, A]): F[List[A]] = o.run
  }

  implicit def eitherCoHasHoist[F0[_], A] = new CoHasHoist[({ type λ[α] = EitherT[F0, A, α] })#λ] {
    type F[T] = F0[T]
    type G[T] = A ∨ T
    def unlift[B](o: EitherT[F, A, B]): F[A ∨ B] = o.run
  }
}

trait Monitored[C <: HList, F[_], A] {
  import Monitored.Context

  val f: Context[C] => F[A]
  def apply(c: Context[C]): F[A] = f(c)

  def flatMap[B](fr: A => Monitored[C, F, B])(implicit m: Monad[F]) =
    Monitored[C, F, B] { (c: Context[C]) =>
      m.bind(apply(c))(a => fr(a)(c))
    }

  def map[B](fu: A => B)(implicit m: Functor[F]) =
    Monitored[C, F, B] { (c: Context[C]) =>
      m.map(f(c))(fu)
    }

  def contramap(endo: Context[C] => Context[C]) =
    Monitored[C, F, A]{ (c: Context[C]) =>
      f(endo(c))
    }

  def map0[G[_], B](fu: F[A] => G[B]) =
    Monitored[C, G, B] { (c: Context[C]) =>
      fu(f(c))
    }

  def run(implicit ch: CoHasHoist[F]) = map0(a => ch.unlift(a))

  def lift[AP[_]](implicit ap: Applicative[AP], fu: Functor[F]): Monitored[C, F, AP[A]] =
    this.map(a => ap.point(a))
}

object Monitored {
  import scalaz.syntax.monad._
  import scalaz.Id._
  import scalaz.Unapply

  case class Context[C <: HList](value: C, span: Context.Span, parents: Array[Context.Id])
  object Context {
    case class Span(value: String) extends AnyVal
    case class Id(value: String) extends AnyVal
    object Id { def gen = Id(java.util.UUID.randomUUID.toString) }
    object Span { def gen = Span(java.util.UUID.randomUUID.toString) }
  }

  trait *->*[F[_]] {}
  trait *->*->*[F[_, _]] {}

  implicit def fKindEv[F0[_]] = new *->*[F0] {}
  implicit def fKindEv2[F0[_, _]] = new *->*->*[F0] {}

  def apply0[C <: HList, A0](λ: Context[C] => A0): Monitored[C, Id, A0] =
    apply[C, Id, A0](λ)

  def apply[C <: HList, F0[_], A0](λ: Context[C] => F0[A0]): Monitored[C, F0, A0] =
    new Monitored[C, F0, A0] {
      val f = λ
    }

  def apply[C <: HList, F[_], A](m: Monitored[C, F, A]): Monitored[C, F, A] =
    m.contramap(c => c.copy(parents = c.parents :+ Context.Id.gen))

  def trans[C <: HList, F[_], G[_]: *->*, A](m: Monitored[C, F, G[A]])(implicit hh: HasHoist[G]): Monitored[C, ({ type λ[α] = hh.T[F, α] })#λ, A] =
    Monitored[C, ({ type λ[α] = hh.T[F, α] })#λ, A] { (c: Context[C]) =>
      hh.lift[F, A](m.f(c))
    }

  def trans[C <: HList, F[_], G[_, _]: *->*->*, A, B](m: Monitored[C, F, G[A, B]])(implicit hh: HasHoist[({ type λ[α] = G[A, α] })#λ]): Monitored[C, ({ type λ[α] = hh.T[F, α] })#λ, B] = {
    type λ[α] = G[A, α]
    trans[C, F, λ, B](m)(new *->*[λ] {}, hh)
  }

  implicit def monitoredInstances[C <: HList, F[_]: Monad, A] =
    new Monad[({ type λ[α] = Monitored[C, F, α] })#λ] {
      def point[A](a: => A): Monitored[C, F, A] = Monitored[C, F, A]((_: Context[C]) => implicitly[Monad[F]].point(a))
      def bind[A, B](m: Monitored[C, F, A])(f: A => Monitored[C, F, B]): Monitored[C, F, B] =
        m.flatMap(f)
    }
}