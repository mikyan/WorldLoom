package io.worldloom.ui.game

import io.worldloom.application.CharacterCreationFieldPresentation
import io.worldloom.application.CharacterCreationOptionPresentation
import io.worldloom.application.CharacterCreationPresentation
import io.worldloom.content.schema.CharacterCreationMode
import io.worldloom.content.schema.CharacterProfileProblem
import io.worldloom.content.schema.CharacterProfileProblemCode
import io.worldloom.definition.BooleanValue
import io.worldloom.definition.DecimalValue
import io.worldloom.definition.DefinitionId
import io.worldloom.definition.DefinitionReferenceValue
import io.worldloom.definition.IntegerValue
import io.worldloom.definition.TextValue
import io.worldloom.world.RunId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CharacterCreationPageTest {
    @Test
    fun `typed values use player readable labels`() {
        assertEquals("是", characterValueLabel(BooleanValue(true)))
        assertEquals("否", characterValueLabel(BooleanValue(false)))
        assertEquals("75", characterValueLabel(IntegerValue(75)))
        assertEquals("12.34", characterValueLabel(DecimalValue(1_234, 2)))
        assertEquals("-0.5", characterValueLabel(DecimalValue(-500, 3)))
        assertEquals("未填写", characterValueLabel(TextValue("")))
        assertEquals("幸存者", characterValueLabel(TextValue("幸存者")))
        assertEquals("已选择", characterValueLabel(DefinitionReferenceValue(id("private.internal-choice"))))
    }

    @Test
    fun `fixed and point buy worlds retain public presentation labels`() {
        val war = presentation(
            worldTitle = "灰烬中的车队",
            mode = CharacterCreationMode.FIXED,
            fieldLabel = "身体状况",
            value = IntegerValue(7),
            option = CharacterCreationOptionPresentation(id("war.character.survivor"), "前线幸存者"),
        ).toCharacterCreationUiModel()
        val station = presentation(
            worldTitle = "静默轨道：赫利俄斯危机",
            mode = CharacterCreationMode.POINT_BUY,
            fieldLabel = "能源储备",
            value = IntegerValue(80),
            budget = 30,
            pointsSpent = 10,
        ).toCharacterCreationUiModel()

        assertEquals("灰烬中的车队", war.worldTitle)
        assertEquals("前线幸存者", war.optionLabel)
        assertEquals(CharacterCreationFieldUi("身体状况", "7"), war.fields.single())
        assertEquals("静默轨道：赫利俄斯危机", station.worldTitle)
        assertEquals(CharacterCreationFieldUi("能源储备", "80"), station.fields.single())
        assertEquals("已分配 10 / 30 点", station.budgetLabel)
        assertFalse(war.fields.single().label.contains("war."))
        assertFalse(station.fields.single().label.contains("station."))
    }

    @Test
    fun `long narrative and validation problem use safe summary text`() {
        val longBackground = "旅".repeat(200)
        val problem = CharacterProfileProblem(
            code = CharacterProfileProblemCode.ASSIGNMENT_OUT_OF_RANGE,
            path = "fields[war.health]",
            message = "war.health must be within internal bounds",
        )
        val model = presentation(
            worldTitle = "测试世界",
            mode = CharacterCreationMode.NARRATIVE,
            fieldLabel = "意志",
            value = IntegerValue(3),
            narrativeBackground = longBackground,
            problems = listOf(problem),
        ).toCharacterCreationUiModel()

        assertEquals(161, model.backgroundPreview?.length)
        assertTrue(model.backgroundPreview?.endsWith("…") == true)
        assertEquals("有一项角色状态超出允许范围。", model.problems.single())
        assertFalse(model.problems.single().contains("war.health"))
        assertFalse(model.canConfirm)
    }

    private fun presentation(
        worldTitle: String,
        mode: CharacterCreationMode,
        fieldLabel: String,
        value: IntegerValue,
        option: CharacterCreationOptionPresentation? = null,
        budget: Int? = null,
        pointsSpent: Int = 0,
        narrativeBackground: String = "",
        problems: List<CharacterProfileProblem> = emptyList(),
    ): CharacterCreationPresentation = CharacterCreationPresentation(
        runId = RunId("run.ui-test"),
        worldId = id("test.world"),
        worldTitle = worldTitle,
        profileId = id("private.profile"),
        playerEntityId = "player",
        modes = listOf(mode),
        selectedMode = mode,
        options = listOfNotNull(option),
        selectedOptionId = option?.id,
        fields = listOf(
            CharacterCreationFieldPresentation(
                componentId = id("private.component"),
                fieldId = id("private.field"),
                label = fieldLabel,
                value = value,
                minimumInteger = 0,
                maximumInteger = 100,
            ),
        ),
        pointBuyBudget = budget,
        pointsSpent = pointsSpent,
        narrativeBackground = narrativeBackground,
        problems = problems,
    )

    private fun id(value: String) = DefinitionId(value)
}
