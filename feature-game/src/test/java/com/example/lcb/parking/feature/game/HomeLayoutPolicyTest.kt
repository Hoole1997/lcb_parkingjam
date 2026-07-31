package com.example.lcb.parking.feature.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutPolicyTest {

    @Test
    fun `reference aspect ratio preserves V4 geometry`() {
        val policy = calculateHomeLayoutPolicy(
            viewportWidthDp = 360f,
            viewportHeightDp = 640f,
        )

        val expectedScale = 360f / HomeDesignGrid.WIDTH
        assertEquals(expectedScale, policy.contentScale, EPSILON)
        assertEquals(0f, policy.contentLeftDp, EPSILON)
        assertEquals(905f * expectedScale, policy.size(HomeDesignGrid.hero.width), EPSILON)
        assertEquals(60f * expectedScale, policy.x(HomeDesignGrid.hero.x), EPSILON)
    }

    @Test
    fun `tall phone increases spacing without stretching artwork`() {
        val policy = calculateHomeLayoutPolicy(
            viewportWidthDp = 360f,
            viewportHeightDp = 800f,
        )

        assertEquals(360f / HomeDesignGrid.WIDTH, policy.contentScale, EPSILON)
        assertEquals(800f / HomeDesignGrid.HEIGHT, policy.verticalAnchorScale, EPSILON)
        assertTrue(policy.verticalAnchorScale > policy.contentScale)
        assertEquals(
            HomeDesignGrid.hero.height * policy.contentScale,
            policy.size(HomeDesignGrid.hero.height),
            EPSILON,
        )
    }

    @Test
    fun `short phone scales whole composition inside viewport`() {
        val policy = calculateHomeLayoutPolicy(
            viewportWidthDp = 360f,
            viewportHeightDp = 600f,
        )
        val lastButtonBottom = policy.y(HomeDesignGrid.levelSelect.y) +
            policy.size(HomeDesignGrid.levelSelect.height)

        assertTrue(policy.contentLeftDp > 0f)
        assertTrue(lastButtonBottom <= policy.viewportHeightDp)
    }

    @Test
    fun `tablet caps artwork width and centers composition`() {
        val policy = calculateHomeLayoutPolicy(
            viewportWidthDp = 800f,
            viewportHeightDp = 1280f,
        )

        assertEquals(600f, policy.size(HomeDesignGrid.WIDTH), EPSILON)
        assertEquals(100f, policy.contentLeftDp, EPSILON)
    }

    private companion object {
        const val EPSILON = 0.01f
    }
}
