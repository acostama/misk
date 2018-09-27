package misk.web.interceptors

import misk.prometheus.PrometheusHistogramRegistryModule
import misk.inject.KAbstractModule
import misk.security.authz.AccessControlModule
import misk.security.authz.Unauthenticated
import misk.security.authz.fake.FakeCallerAuthenticator.Companion.SERVICE_HEADER
import misk.security.authz.fake.FakeCallerAuthenticatorModule
import misk.testing.MiskTest
import misk.testing.MiskTestModule
import misk.web.Get
import misk.web.PathParam
import misk.web.Response
import misk.web.actions.WebActionEntry
import misk.web.WebTestingModule
import misk.web.actions.WebAction
import misk.web.jetty.JettyService
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import javax.inject.Inject

@MiskTest(startService = true)
class MetricsInterceptorTest {
  @MiskTestModule
  val module = TestModule()
  val httpClient = OkHttpClient()

  @Inject private lateinit var metricsInterceptorFactory: MetricsInterceptor.Factory
  @Inject private lateinit var jettyService: JettyService

  @BeforeEach
  fun sendRequests() {
    assertThat(invoke(200).code()).isEqualTo(200)
    assertThat(invoke(200).code()).isEqualTo(200)
    assertThat(invoke(202).code()).isEqualTo(202)
    assertThat(invoke(404).code()).isEqualTo(404)
    assertThat(invoke(403).code()).isEqualTo(403)
    assertThat(invoke(403).code()).isEqualTo(403)
    assertThat(invoke(200, "my-peer").code()).isEqualTo(200)
    assertThat(invoke(200, "my-peer").code()).isEqualTo(200)
    assertThat(invoke(200, "my-peer").code()).isEqualTo(200)
    assertThat(invoke(200, "my-peer").code()).isEqualTo(200)
  }

  @Test
  fun responseCodes() {
    val requestDuration = metricsInterceptorFactory.requestDuration
    requestDuration.record(1.0,"TestAction", "unknown", "all")
    assertThat(requestDuration.count()).isEqualTo(7)
    requestDuration.record(1.0, "TestAction", "unknown", "2xx")
    assertThat(requestDuration.count()).isEqualTo(4)
    requestDuration.record(1.0, "TestAction", "unknown", "200")
    assertThat(requestDuration.count()).isEqualTo(3)
    requestDuration.record(1.0, "TestAction", "unknown", "202")
    assertThat(requestDuration.count()).isEqualTo(2)
    requestDuration.record(1.0, "TestAction", "unknown", "4xx")
    assertThat(requestDuration.count()).isEqualTo(4)
    requestDuration.record(1.0, "TestAction", "unknown", "404")
    assertThat(requestDuration.count()).isEqualTo(2)
    requestDuration.record(1.0, "TestAction", "unknown", "403")
    assertThat(requestDuration.count()).isEqualTo(3)

    requestDuration.record(1.0, "TestAction", "my-peer", "all")
    assertThat(requestDuration.count()).isEqualTo(5)
    requestDuration.record(1.0, "TestAction", "my-peer", "2xx")
    assertThat(requestDuration.count()).isEqualTo(5)
    requestDuration.record(1.0,"TestAction", "my-peer", "200")
    assertThat(requestDuration.count()).isEqualTo(5)
  }

  fun invoke(desiredStatusCode: Int, service: String? = null): okhttp3.Response {
    val url = jettyService.httpServerUrl.newBuilder()
        .encodedPath("/call/$desiredStatusCode")
        .build()

    val request = okhttp3.Request.Builder()
        .url(url)
        .get()
    service?.let { request.addHeader(SERVICE_HEADER, it) }
    return httpClient.newCall(request.build()).execute()
  }

  internal class TestAction : WebAction {
    @Get("/call/{desiredStatusCode}")
    @Unauthenticated
    fun call(@PathParam desiredStatusCode: Int): Response<String> {
      return Response("foo", statusCode = desiredStatusCode)
    }
  }

  class TestModule : KAbstractModule() {
    override fun configure() {
      install(AccessControlModule())
      install(WebTestingModule())
      install(FakeCallerAuthenticatorModule())
      install(PrometheusHistogramRegistryModule())
      multibind<WebActionEntry>().toInstance(WebActionEntry<TestAction>())
    }
  }

}
