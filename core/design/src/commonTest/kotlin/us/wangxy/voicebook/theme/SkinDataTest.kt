package us.wangxy.voicebook.theme

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 测试用的内存 ThemeStore 实现 */
class TestThemeStore : ThemeStore {
    var preference = ThemePreference()
    var customSkins = emptyList<SkinData>()

    override fun load(): ThemePreference = preference
    override fun save(preference: ThemePreference) { this.preference = preference }
    override fun loadCustomSkins(): List<SkinData> = customSkins
    override fun saveCustomSkins(skins: List<SkinData>) { customSkins = skins }
}

class SkinDataTest {

    @Test
    fun builtinSkinDataRoundtrip() {
        val builtin = SkinData.builtinSkinData()
        assertEquals(3, builtin.size)
        assertEquals("fold", builtin[0].id)
        assertEquals("tide", builtin[1].id)
        assertEquals("neon", builtin[2].id)

        // 每个内置皮肤：toSkinSpec -> fromSkinSpec -> encode/decode 应该保持一致
        builtin.forEach { original ->
            val spec = original.toSkinSpec()
            val roundtripped = SkinData.fromSkinSpec(original.id, original.name, spec)
            val encoded = SkinDataCodec.encode(roundtripped)
            val decoded = SkinDataCodec.decode(encoded)

            assertEquals(original.id, decoded.id)
            assertEquals(original.name, decoded.name)
            assertEquals(original.version, decoded.version)
            assertEquals(original.light.primary, decoded.light.primary)
            assertEquals(original.dark.primary, decoded.dark.primary)
            assertEquals(original.shape.topStart, decoded.shape.topStart)
            assertEquals(original.shape.smoothing, decoded.shape.smoothing, 0.001f)
            assertEquals(original.twine?.light?.paper, decoded.twine?.light?.paper)
            assertEquals(original.twine?.dark?.paper, decoded.twine?.dark?.paper)
        }
    }

    @Test
    fun importSkinConflictOverridesCustom() = runTest {
        val store = TestThemeStore()
        val controller = ThemeController(store)

        // 先导入一个自定义皮肤
        val customJson = SkinDataCodec.encode(
            SkinData(
                id = "my-skin",
                name = "我的皮肤",
                light = SkinColors(primary = "#FF123456"),
                dark = SkinColors(primary = "#FF654321"),
                shape = SkinShape(topStart = 10, topEnd = 10, bottomEnd = 10, bottomStart = 10),
            )
        )
        controller.importSkin(customJson).getOrThrow()
        assertEquals(1, controller.customSkins.value.size)
        assertEquals("我的皮肤", controller.customSkins.value[0].name)

        // 再次导入同 ID 但不同名称的皮肤，应该覆盖
        val customJson2 = SkinDataCodec.encode(
            SkinData(
                id = "my-skin",
                name = "我的皮肤 v2",
                light = SkinColors(primary = "#FFABCDEF"),
                dark = SkinColors(primary = "#FFDEF012"),
                shape = SkinShape(topStart = 12, topEnd = 12, bottomEnd = 12, bottomStart = 12),
            )
        )
        controller.importSkin(customJson2).getOrThrow()
        assertEquals(1, controller.customSkins.value.size)
        assertEquals("我的皮肤 v2", controller.customSkins.value[0].name)
        assertEquals("#FFABCDEF", controller.customSkins.value[0].light.primary)
    }

    @Test
    fun importSkinCannotOverrideBuiltin() = runTest {
        val store = TestThemeStore()
        val controller = ThemeController(store)

        // 尝试导入覆盖内置 fold 皮肤
        val builtinFold = SkinData.builtinSkinData().first { it.id == "fold" }
        val evilJson = SkinDataCodec.encode(builtinFold.copy(name = "邪恶折纸"))
        val result = controller.importSkin(evilJson)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("内置") == true)
        assertTrue(controller.customSkins.value.isEmpty())

        val systemJson = SkinDataCodec.encode(builtinFold.copy(id = "system", name = "假系统色"))
        val systemResult = controller.importSkin(systemJson)
        assertTrue(systemResult.isFailure)
        assertTrue(controller.customSkins.value.isEmpty())
    }

    @Test
    fun legacyEnumSkinIdMigrates() {
        assertEquals("system", null.toSkinId())
        assertEquals("system", "  ".toSkinId())
        assertEquals("system", "System".toSkinId())
        assertEquals("dynamic", "Dynamic".toSkinId())
        assertEquals("fold", "Fold".toSkinId())
        assertEquals("tide", "Tide".toSkinId())
        assertEquals("neon", "Neon".toSkinId())
        assertEquals("system", "system".toSkinId())
        assertEquals("my-skin", "my-skin".toSkinId())
    }

    @Test
    fun deleteCurrentSkinFallbacksToSystem() = runTest {
        val store = TestThemeStore()
        val controller = ThemeController(store)

        // 先导入一个自定义皮肤并设为当前
        val customJson = SkinDataCodec.encode(
            SkinData(
                id = "deletable-skin",
                name = "可删除皮肤",
                light = SkinColors(primary = "#FF112233"),
                dark = SkinColors(primary = "#FF332211"),
                shape = SkinShape(topStart = 10, topEnd = 10, bottomEnd = 10, bottomStart = 10),
            )
        )
        controller.importSkin(customJson).getOrThrow()
        controller.setSkin("deletable-skin")
        assertEquals("deletable-skin", controller.preference.value.skin)

        // 删除当前皮肤，应该回落到 system
        controller.removeCustomSkin("deletable-skin")
        assertEquals("system", controller.preference.value.skin)
        assertTrue(controller.customSkins.value.isEmpty())
    }

    @Test
    fun deleteNonCurrentSkinKeepsCurrent() = runTest {
        val store = TestThemeStore()
        val controller = ThemeController(store)

        // 导入两个自定义皮肤，设置第一个为当前
        val skin1Json = SkinDataCodec.encode(
            SkinData(
                id = "skin1",
                name = "皮肤1",
                light = SkinColors(primary = "#FF111111"),
                dark = SkinColors(primary = "#FF222222"),
                shape = SkinShape(topStart = 10, topEnd = 10, bottomEnd = 10, bottomStart = 10),
            )
        )
        val skin2Json = SkinDataCodec.encode(
            SkinData(
                id = "skin2",
                name = "皮肤2",
                light = SkinColors(primary = "#FF333333"),
                dark = SkinColors(primary = "#FF444444"),
                shape = SkinShape(topStart = 10, topEnd = 10, bottomEnd = 10, bottomStart = 10),
            )
        )
        controller.importSkin(skin1Json).getOrThrow()
        controller.importSkin(skin2Json).getOrThrow()
        controller.setSkin("skin1")
        assertEquals("skin1", controller.preference.value.skin)

        // 删除非当前皮肤 skin2，当前皮肤应保持
        controller.removeCustomSkin("skin2")
        assertEquals("skin1", controller.preference.value.skin)
        assertEquals(1, controller.customSkins.value.size)
        assertEquals("skin1", controller.customSkins.value[0].id)
    }

    @Test
    fun exportBuiltinSkin() = runTest {
        val store = TestThemeStore()
        val controller = ThemeController(store)

        // 导出内置皮肤 fold
        val exported = controller.exportSkin("fold")
        assertNotNull(exported)

        val decoded = SkinDataCodec.decode(exported!!)
        assertEquals("fold", decoded.id)
        assertEquals("折纸", decoded.name)
    }

    @Test
    fun exportCustomSkin() = runTest {
        val store = TestThemeStore()
        val controller = ThemeController(store)

        val customJson = SkinDataCodec.encode(
            SkinData(
                id = "export-test",
                name = "导出测试",
                light = SkinColors(primary = "#FF123456"),
                dark = SkinColors(primary = "#FF654321"),
                shape = SkinShape(topStart = 10, topEnd = 10, bottomEnd = 10, bottomStart = 10),
            )
        )
        controller.importSkin(customJson).getOrThrow()

        val exported = controller.exportSkin("export-test")
        assertNotNull(exported)

        val decoded = SkinDataCodec.decode(exported!!)
        assertEquals("export-test", decoded.id)
        assertEquals("导出测试", decoded.name)
    }

    @Test
    fun exportNonExistentSkinReturnsNull() = runTest {
        val store = TestThemeStore()
        val controller = ThemeController(store)

        val exported = controller.exportSkin("non-existent")
        assertNull(exported)
    }
}