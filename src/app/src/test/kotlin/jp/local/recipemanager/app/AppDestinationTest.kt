package jp.local.recipemanager.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDestinationTest {
    @Test
    fun routesAreUnique() {
        val routes = AppDestination.entries.map(AppDestination::route)

        assertEquals(routes.size, routes.distinct().size)
    }

    @Test
    fun bottomNavigationHasFiveDestinations() {
        val destinations = AppDestination.topLevelDestinations

        assertEquals(5, destinations.size)
        assertTrue(destinations.contains(AppDestination.HOME))
        assertTrue(destinations.contains(AppDestination.MORE))
    }
}

