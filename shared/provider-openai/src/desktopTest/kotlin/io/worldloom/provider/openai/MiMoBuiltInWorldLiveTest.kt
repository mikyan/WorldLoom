package io.worldloom.provider.openai

import io.ktor.client.HttpClient
import io.worldloom.agent.runtime.AgentRunPolicy
import io.worldloom.agent.runtime.AgentRuntime
import io.worldloom.agent.runtime.DefaultAgentToolGateway
import io.worldloom.agent.runtime.DefaultGameAgentController
import io.worldloom.agent.runtime.GameAgentState
import io.worldloom.agent.runtime.GameTurnStatus
import io.worldloom.application.ActionResult
import io.worldloom.application.DefaultGameSession
import io.worldloom.application.GameSessionUiState
import io.worldloom.application.LoadResult
import io.worldloom.application.SequentialSessionIdSource
import io.worldloom.application.StaticWorldCatalog
import io.worldloom.application.StaticWorldCatalogResult
import io.worldloom.application.WorldPackageSource
import io.worldloom.platform.credentials.CredentialReadResult
import io.worldloom.platform.credentials.CredentialVault
import io.worldloom.platform.credentials.CredentialWriteResult
import io.worldloom.platform.credentials.SecretValue
import io.worldloom.platform.credentials.SessionCredentialVault
import io.worldloom.platform.credentials.createDesktopCredentialVault
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Opt-in network smoke tests for the complete MiMo -> Agent -> Tool -> Event -> Presentation path.
 * The dedicated `mimoLiveTest` task runs these sequentially; normal `check` excludes this class.
 */
class MiMoBuiltInWorldLiveTest {
    @Test
    fun warSurvivalStaysHealthyForTenModelTurns() = runBlocking {
        if (!liveTestEnabled()) return@runBlocking
        runWorld(
            directory = "war-survival",
            runPrefix = "mimo-live-war",
            prompts = WAR_PROMPTS,
        )
    }

    @Test
    fun stationAiStaysHealthyForTenModelTurns() = runBlocking {
        if (!liveTestEnabled()) return@runBlocking
        runWorld(
            directory = "station-ai",
            runPrefix = "mimo-live-station",
            prompts = STATION_PROMPTS,
        )
    }

    private suspend fun runWorld(
        directory: String,
        runPrefix: String,
        prompts: List<String>,
    ) {
        assertEquals(TURN_COUNT, prompts.size)
        val source = OpenAiSubscriptionSources.MiMoTokenPlanCn
        assertEquals("mimo-v2.5", source.modelId)
        val client = createOpenAiHttpClient()
        try {
            val provider = OpenAiChatCompletionsProvider(
                httpClient = client,
                credentialVault = liveCredentialVault(source),
                config = OpenAiChatCompletionsConfig(
                    model = source.modelId,
                    baseUrl = source.baseUrl,
                ),
                credentialKey = source.credentialKey,
            )
            val catalog = catalog(directory)
            val session = DefaultGameSession(
                catalog = catalog,
                idSource = SequentialSessionIdSource(runPrefix),
            )
            assertIs<LoadResult.Success>(session.load(catalog.entries.single().id))
            assertIs<ActionResult.Success>(session.confirmCharacter())
            val initialSequence = assertIs<GameSessionUiState.Ready>(session.state.value)
                .presentation.lastSequence
            val gateway = DefaultAgentToolGateway(session)
            val controller = DefaultGameAgentController(
                runtime = AgentRuntime(
                    provider = provider,
                    toolGateway = gateway,
                    policy = AgentRunPolicy(
                        maxSteps = 8,
                        maxToolCalls = 8,
                        timeoutMillis = 120_000,
                        maxInputTokens = 96_000,
                        maxOutputTokens = 4_000,
                        maxOutputTokensPerStep = 1_024,
                    ),
                ),
                gameSession = session,
                directToolGateway = gateway,
            )

            var previousSequence = initialSequence
            prompts.forEachIndexed { index, prompt ->
                controller.send(prompt)
                var state = controller.state.value
                if (state is GameAgentState.AwaitingCheck) {
                    controller.rollPendingCheck(state.turnId)
                    state = controller.state.value
                }
                when (state) {
                    is GameAgentState.Completed -> assertTrue(
                        state.text.isNotBlank(),
                        "$directory turn ${index + 1} returned blank narration",
                    )
                    is GameAgentState.AwaitingPlayer -> assertTrue(
                        state.question.isNotBlank(),
                        "$directory turn ${index + 1} returned a blank clarification",
                    )
                    is GameAgentState.Failed -> fail(
                        "$directory turn ${index + 1} failed safely: ${state.message}",
                    )
                    is GameAgentState.AwaitingCheck,
                    GameAgentState.Idle,
                    is GameAgentState.Running,
                    -> fail("$directory turn ${index + 1} did not reach a terminal state: $state")
                }
                val ready = assertIs<GameSessionUiState.Ready>(
                    session.state.value,
                    "$directory ended or left the playable state before ten turns",
                )
                assertTrue(
                    ready.presentation.lastSequence >= previousSequence,
                    "$directory EventLog sequence moved backwards on turn ${index + 1}",
                )
                previousSequence = ready.presentation.lastSequence
            }

            controller.refreshHistory()
            val history = controller.history.value
            assertEquals(TURN_COUNT, history.items.size, "$directory did not retain all ten GM turns")
            assertTrue(history.issues.isEmpty(), "$directory produced corrupt or future GM history")
            assertTrue(
                history.items.all { it.status in setOf(GameTurnStatus.COMPLETED, GameTurnStatus.AWAITING_PLAYER) },
                "$directory retained a failed or interrupted GM turn",
            )
            assertTrue(
                previousSequence > initialSequence,
                "$directory completed ten conversations but never committed the requested final action",
            )
        } finally {
            client.close()
        }
    }

    private suspend fun liveCredentialVault(source: OpenAiSubscriptionSource): CredentialVault {
        val root = repositoryRoot()
        val rawCredential = readEnvValue(root.resolve(LIVE_ENV_PATH), LIVE_KEY_NAME)
        if (rawCredential != null) {
            val vault = SessionCredentialVault()
            assertIs<CredentialWriteResult.Success>(
                vault.write(source.credentialKey, SecretValue.create(rawCredential)),
            )
            return vault
        }

        val vault = createDesktopCredentialVault(root.resolve(LIVE_CREDENTIAL_DIRECTORY))
        when (vault.read(source.credentialKey)) {
            is CredentialReadResult.Success -> return vault
            is CredentialReadResult.Failure -> error(
                "MiMo live credential is missing. Configure $LIVE_ENV_PATH with $LIVE_KEY_NAME " +
                    "or place the encrypted Desktop credential in $LIVE_CREDENTIAL_DIRECTORY.",
            )
        }
    }

    private fun catalog(directory: String): StaticWorldCatalog {
        val source = WorldPackageSource(
            manifestJson = resource("$directory/manifest.json"),
            files = mapOf(
                "world.json" to resource("$directory/world.json"),
                "playable-world.json" to resource("$directory/playable-world.json"),
                "character-profile.json" to resource("$directory/character-profile.json"),
                "behaviors/activity-starts-quest.json" to
                    resource("$directory/behaviors/activity-starts-quest.json"),
                "behaviors/quest-raises-threat.json" to
                    resource("$directory/behaviors/quest-raises-threat.json"),
                "behaviors/timed-supply.json" to resource("$directory/behaviors/timed-supply.json"),
            ),
        )
        return assertIs<StaticWorldCatalogResult.Success>(
            StaticWorldCatalog.fromPackageSources(listOf(source)),
        ).catalog
    }

    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader.getResource(path)) { "Missing test resource $path" }.readText()

    private fun repositoryRoot(): Path = generateSequence(
        Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize(),
        Path::getParent,
    ).firstOrNull { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        ?: error("Unable to locate the Worldloom repository root")

    private fun readEnvValue(path: Path, key: String): String? {
        if (!Files.isRegularFile(path)) return null
        return Files.readAllLines(path).asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .map { it.removePrefix("export ").removePrefix("\uFEFF") }
            .firstOrNull { it.substringBefore('=', missingDelimiterValue = "") == key }
            ?.substringAfter('=')
            ?.trim()
            ?.removeSurrounding("\"")
            ?.removeSurrounding("'")
            ?.takeIf(String::isNotBlank)
    }

    private fun liveTestEnabled(): Boolean =
        System.getProperty(LIVE_TEST_PROPERTY).equals("true", ignoreCase = true)

    private companion object {
        const val TURN_COUNT = 10
        const val LIVE_TEST_PROPERTY = "worldloom.live.mimo"
        const val LIVE_ENV_PATH = ".worldloom-live/mimo.env"
        const val LIVE_CREDENTIAL_DIRECTORY = ".worldloom-live/credentials"
        const val LIVE_KEY_NAME = "WORLDLOOM_MIMO_TOKEN_PLAN_API_KEY"

        val WAR_PROMPTS = listOf(
            "本轮只做简短主持说明，不调用工具：用一句话确认我们所在的位置。",
            "本轮不调用工具：概括当前最紧迫的危险，不新增世界事实。",
            "本轮不调用工具：用一句话提醒我的长期游戏目标。",
            "本轮不调用工具：概括玛拉在公开场景中的立场，不替她揭示秘密。",
            "本轮不调用工具：概括托马斯在公开场景中的立场，不替他揭示秘密。",
            "本轮不调用工具：说明当前可执行的关键行动有几项。",
            "本轮不调用工具：提醒我做检定时会由本地规则引擎裁决。",
            "本轮不调用工具：简短复述目前已经公开的信息，不推进时间。",
            "本轮不调用工具：为下一步行动给一句气氛化提示，但不要替我行动。",
            "现在执行当前可用的第一项关键行动；必须通过工具提交事实，然后简短叙述结果。",
        )

        val STATION_PROMPTS = listOf(
            "本轮只做简短主持说明，不调用工具：用一句话确认空间站当前场景。",
            "本轮不调用工具：概括眼前最紧迫的系统风险，不新增世界事实。",
            "本轮不调用工具：用一句话提醒我的长期游戏目标。",
            "本轮不调用工具：概括莱拉在公开场景中的职责，不替她揭示秘密。",
            "本轮不调用工具：说明当前能源状态属于事实投影而不是模型猜测。",
            "本轮不调用工具：说明当前可执行的关键行动有几项。",
            "本轮不调用工具：提醒我检定和数值变化由本地规则引擎裁决。",
            "本轮不调用工具：简短复述目前已经公开的信息，不推进时间。",
            "本轮不调用工具：为下一步行动给一句科幻氛围提示，但不要替我行动。",
            "现在执行当前可用的第一项关键行动；必须通过工具提交事实，然后简短叙述结果。",
        )
    }
}
