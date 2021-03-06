package com.github.mwegrz.scalautil.akka.http.server.directives

import akka.http.scaladsl.server.Directives.{ AsyncAuthenticator, Authenticator }
import akka.http.scaladsl.server.directives.Credentials
import akka.http.scaladsl.server.directives.Credentials.{ Missing, Provided }
import com.github.mwegrz.scalautil.store.KeyValueStore
import com.typesafe.config.Config
import pdi.jwt.{ JwtAlgorithm, JwtClaim }
import com.github.mwegrz.scalautil.jwt.decode
import com.github.mwegrz.scalautil.ocupoly.OcupolyJwtClaim

import scala.concurrent.{ ExecutionContext, Future }

object SecurityDirectives {
  trait SaltAndHashedSecret {
    def salt: String
    def hashedSecret: String
  }

  def keyValueStoreAuthenticator[K, V <: SaltAndHashedSecret](
      idToKey: String => K,
      hashSecret: (String, String) => String
  )(implicit
      store: KeyValueStore[K, V],
      executionContext: ExecutionContext
  ): AsyncAuthenticator[V] = {
    case credentials @ Credentials.Provided(id) =>
      store.retrieve(idToKey(id)).map {
        case Some(client) if credentials.verify(client.hashedSecret, secret => hashSecret(secret, client.salt)) =>
          Some(client)
        case _ => None
      }
    case _ => Future.successful(None)
  }

  def ocupolyJwtAuthenticator(config: Config): Authenticator[OcupolyJwtClaim] =
    credentials => jwtAuthenticator(config)(credentials).map(OcupolyJwtClaim.fromJwtClaim)

  def jwtAuthenticator(config: Config): Authenticator[JwtClaim] = {
    val key = config.getString("key")
    val algorithm = JwtAlgorithm.fromString(config.getString("algorithm"))
    jwtAuthenticator(key, algorithm)
  }

  def jwtAuthenticator(key: String, algorithm: JwtAlgorithm): Authenticator[JwtClaim] = {
    case Provided(id) => decode(id, key, algorithm).toOption
    case Missing      => None
  }
}
