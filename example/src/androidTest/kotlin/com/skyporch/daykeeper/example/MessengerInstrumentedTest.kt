package com.skyporch.daykeeper.example

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.containsString
import org.junit.Test
import org.junit.runner.RunWith

/** Real-device UI tests against the clearly labeled offline demo, not a deployed gateway. */
@RunWith(AndroidJUnit4::class)
class MessengerInstrumentedTest {
    @Test
    fun firstConversationSendAndSignOut() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("New conversation")).perform(click())
            onView(withContentDescription("Message"))
                .perform(replaceText("A native support message"), closeSoftKeyboard())
            onView(withText("Send message")).perform(click())
            onView(withText(containsString("You\nA native support message")))
                .check(matches(isDisplayed()))
            onView(withText("Conversations")).perform(click())
            onView(withText(containsString("Conversation 1 · open · 0 unread"))).perform(click())
            onView(withText("Mark read")).perform(click())
            onView(withText("Sign out of support")).perform(click())
            onView(withText("Signed out of support. Sign in again through the app."))
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun draftSurvivesRotationAndBackgroundButNotCustomerSwitch() {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            onView(withText("New conversation")).perform(click())
            onView(withContentDescription("Message"))
                .perform(replaceText("Private draft"), closeSoftKeyboard())
            activity.recreate()
            onView(withContentDescription("Message")).check(matches(withText("Private draft")))
            activity.moveToState(Lifecycle.State.CREATED)
            activity.moveToState(Lifecycle.State.RESUMED)
            onView(withContentDescription("Message")).check(matches(withText("Private draft")))
            onView(withText("Switch demo customer")).perform(click())
            onView(withText("No conversations yet. Start one when you need help."))
                .check(matches(isDisplayed()))
            onView(withText("New conversation")).perform(click())
            onView(withContentDescription("Message")).check(matches(withText("")))
        }
    }
}
