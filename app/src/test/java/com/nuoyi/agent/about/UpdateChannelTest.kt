package com.nuoyi.agent.about

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 自有发布渠道的取数顺序:官网清单优先、失败才读仓库镜像;测试注入的地址只问它自己。
 * 用拦截器把两个真实域名重写到本地 MockWebServer,不依赖外网。
 */
class UpdateChannelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val hits = mutableListOf<String>()
    private var siteBody: String? = null
    private var mirrorBody: String? = null

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                hits += path
                val body = if (path.startsWith("/site-api")) siteBody else mirrorBody
                return if (body == null) MockResponse().setResponseCode(530) else MockResponse().setBody(body)
            }
        }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    /** 把官网/raw 域名都指向本地服务,按域名分到不同路径以便分别设定响应。 */
    private fun client() = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val url = chain.request().url
            val path = if (url.host == "noy.127068.xyz") "/site-api" else "/mirror-api"
            val rewritten = url.newBuilder()
                .scheme("http")
                .host(server.hostName)
                .port(server.port)
                .encodedPath(path)
                .build()
            chain.proceed(chain.request().newBuilder().url(rewritten).build())
        }.build()

    private fun checker(releaseApi: String) = UpdateChecker(
        tmp.root.resolve("update.json"), "0.1.0",
        client = client(), releaseApi = releaseApi,
        now = { 1_000_000_000L },
    )

    private fun manifest(tag: String) = """{
        "tag_name":"$tag","name":"诺伊 $tag","body":"- 新版本",
        "html_url":"https://noy.127068.xyz/download/","published_at":"2026-11-10T00:00:00Z",
        "assets":[]
    }"""

    @Test
    fun officialManifestIsTriedFirst() = runTest {
        siteBody = manifest("v0.2.0")
        val c = checker(NuoyiLinks.LATEST_RELEASE_API)
        c.check(manual = true)
        assertEquals("0.2.0", c.state.value.update?.version)
        assertEquals("https://noy.127068.xyz/download/", c.state.value.latest?.pageUrl)
        assertEquals(listOf("/site-api"), hits)
    }

    @Test
    fun siteOutageFallsBackToRawMirror() = runTest {
        mirrorBody = manifest("v0.2.0")
        val c = checker(NuoyiLinks.LATEST_RELEASE_API)
        c.check(manual = true)
        assertEquals("0.2.0", c.state.value.update?.version)
        assertEquals(listOf("/site-api", "/mirror-api"), hits)
    }

    @Test
    fun personalEditionUsesItsOwnManifest() = runTest {
        mirrorBody = manifest("v0.2.0")
        val c = checker(NuoyiLinks.PERSONAL_LATEST_RELEASE_API)
        c.check(manual = true)
        assertEquals("0.2.0", c.state.value.update?.version)
        assertEquals(listOf("/site-api", "/mirror-api"), hits)
    }

    /** 注入的地址只问它自己:不会在测试里偷偷去访问真实镜像。 */
    @Test
    fun injectedEndpointHasNoFallback() = runTest {
        val c = checker("https://example.test/api")
        c.check(manual = true)
        assertEquals(listOf("/mirror-api"), hits)
        assertNull(c.state.value.update)
    }

    @Test
    fun channelIsReadyAndPointsToOwnDomains() {
        assertTrue(NuoyiLinks.UPDATES_CHANNEL_READY)
        assertTrue(NuoyiLinks.LATEST_RELEASE_API.startsWith("https://noy.127068.xyz/"))
        assertTrue(NuoyiLinks.PERSONAL_LATEST_RELEASE_API.startsWith("https://noy.127068.xyz/"))
        assertTrue(NuoyiLinks.LATEST_RELEASE_MIRROR.startsWith("https://raw.githubusercontent.com/cnwhli/nuoyi/"))
        assertTrue(NuoyiLinks.RELEASES.startsWith("https://github.com/cnwhli/nuoyi/"))
    }
}
