package jp.local.recipemanager.feature.menu

import jp.local.recipemanager.data.local.MenuDao
import jp.local.recipemanager.data.local.MenuDayEntity
import jp.local.recipemanager.data.local.MenuItemEntity
import jp.local.recipemanager.domain.model.MenuRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class MenuRulesTest {
    private val day = MenuDayEntity("day", "week", LocalDate.of(2026, 9, 21), false, "", "")
    private fun item(role: MenuRole) = MenuItemEntity(role.name, day.id, "recipe-${role.name}", role.name, role)

    @Test fun mondayOfReturnsStartOfWeek() {
        assertEquals(LocalDate.of(2026, 9, 21), MenuRepository.mondayOf(LocalDate.of(2026, 9, 25)))
    }

    @Test fun oneDishAndThreeDishMenusAreAccepted() {
        MenuDao.validateDay(day, listOf(item(MenuRole.ONE_DISH)))
        MenuDao.validateDay(day, listOf(item(MenuRole.MAIN), item(MenuRole.SIDE), item(MenuRole.SOUP)))
    }

    @Test fun incompleteMenuIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { MenuDao.validateDay(day, listOf(item(MenuRole.MAIN))) }
    }

    @Test fun noMealDayRejectsMenuItems() {
        assertThrows(IllegalArgumentException::class.java) { MenuDao.validateDay(day.copy(noMeal = true), listOf(item(MenuRole.ONE_DISH))) }
        MenuDao.validateDay(day.copy(noMeal = true), emptyList())
    }
}
