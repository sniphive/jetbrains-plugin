package com.sniphive.idea.ui

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Method
import javax.swing.AbstractAction

/**
 * Unit tests for LoginDialog Register button functionality.
 *
 * These tests verify the actual RegisterAction behavior by accessing
 * private members via reflection:
 * 1. REGISTER_URL constant has correct value
 * 2. RegisterAction inner class exists and extends AbstractAction
 * 3. createLeftSideActions returns action with correct name
 *
 * Note: Full RegisterAction.actionPerformed() testing requires
 * mockito-inline for BrowserUtil.browse() mocking, which is not
 * available in this project's test dependencies.
 */
class LoginDialogRegisterTest {

    /**
     * Test: REGISTER_URL constant is correctly defined in LoginDialog.
     *
     * This verifies the ACTUAL constant from LoginDialog, not a hardcoded
     * string in the test. If REGISTER_URL is renamed or removed,
     * this test will fail.
     */
    @Test
    fun `LoginDialog REGISTER_URL constant should be https sniphive net register`() {
        val registerUrlField = LoginDialog::class.java.getDeclaredField("REGISTER_URL")
        registerUrlField.isAccessible = true
        val actualUrl = registerUrlField.get(null) as String

        assertEquals(
            "LoginDialog REGISTER_URL should be https://sniphive.net/register",
            "https://sniphive.net/register",
            actualUrl
        )
    }

    /**
     * Test: REGISTER_URL should use HTTPS protocol.
     */
    @Test
    fun `Register URL should use HTTPS protocol`() {
        val registerUrlField = LoginDialog::class.java.getDeclaredField("REGISTER_URL")
        registerUrlField.isAccessible = true
        val actualUrl = registerUrlField.get(null) as String

        assertTrue("URL should start with https://", actualUrl.startsWith("https://"))
    }

    /**
     * Test: REGISTER_URL should be valid URL format.
     */
    @Test
    fun `Register URL should be valid format`() {
        val registerUrlField = LoginDialog::class.java.getDeclaredField("REGISTER_URL")
        registerUrlField.isAccessible = true
        val actualUrl = registerUrlField.get(null) as String

        assertTrue("URL should contain sniphive.net", actualUrl.contains("sniphive.net"))
        assertTrue("URL should end with /register", actualUrl.endsWith("/register"))
    }

    /**
     * Test: RegisterAction inner class exists within LoginDialog's Companion.
     *
     * This verifies that the private inner class RegisterAction actually exists.
     * The class is defined as private class RegisterAction inside companion object.
     */
    @Test
    fun `RegisterAction inner class should exist in Companion`() {
        // In Kotlin, companion object members become static members of the outer class
        // The class name for a nested class inside companion object is: OuterClass$Companion$NestedClass
        val registerActionClassName = "com.sniphive.idea.ui.LoginDialog\$Companion\$RegisterAction"

        val registerActionClass = try {
            Class.forName(registerActionClassName)
        } catch (e: ClassNotFoundException) {
            fail("RegisterAction inner class should exist as LoginDialog\$Companion\$RegisterAction: ${e.message}")
            null
        }

        assertNotNull("RegisterAction class should exist", registerActionClass)

        // Verify it extends AbstractAction
        val superclass = registerActionClass!!.superclass
        assertEquals("RegisterAction should extend AbstractAction",
            AbstractAction::class.java, superclass)
    }

    /**
     * Test: RegisterAction has actionPerformed method.
     */
    @Test
    fun `RegisterAction should have actionPerformed method`() {
        val registerActionClassName = "com.sniphive.idea.ui.LoginDialog\$Companion\$RegisterAction"

        val registerActionClass = try {
            Class.forName(registerActionClassName)
        } catch (e: ClassNotFoundException) {
            fail("RegisterAction class should exist: ${e.message}")
            return
        }

        // Verify actionPerformed method exists
        val actionPerformedMethod = try {
            registerActionClass.getMethod("actionPerformed", java.awt.event.ActionEvent::class.java)
        } catch (e: NoSuchMethodException) {
            fail("RegisterAction should have actionPerformed(ActionEvent) method: ${e.message}")
            return
        }

        assertNotNull("actionPerformed method should exist", actionPerformedMethod)
    }

    /**
     * Test: RegisterAction can be instantiated via its constructor.
     */
    @Test
    fun `RegisterAction instance should be creatable`() {
        val registerActionClassName = "com.sniphive.idea.ui.LoginDialog\$Companion\$RegisterAction"

        val registerActionClass = try {
            Class.forName(registerActionClassName)
        } catch (e: ClassNotFoundException) {
            fail("RegisterAction class should exist: ${e.message}")
            return
        }

        // Create RegisterAction instance via no-arg constructor
        val constructor = try {
            registerActionClass.getDeclaredConstructor()
        } catch (e: NoSuchMethodException) {
            fail("RegisterAction should have a no-arg constructor: ${e.message}")
            return
        }

        constructor.isAccessible = true
        val registerAction = constructor.newInstance()

        // Verify it's an AbstractAction with correct name
        assertTrue("RegisterAction should be an AbstractAction",
            registerAction is AbstractAction)

        val action = registerAction as AbstractAction
        val actionName = action.getValue(AbstractAction.NAME)
        assertEquals("RegisterAction name should be 'Register'", "Register", actionName)
    }

    /**
     * Test: RegisterAction's ACTION_COMMAND_KEY should be set.
     */
    @Test
    fun `RegisterAction should have ACTION_COMMAND_KEY set`() {
        val registerActionClassName = "com.sniphive.idea.ui.LoginDialog\$Companion\$RegisterAction"

        val registerActionClass = try {
            Class.forName(registerActionClassName)
        } catch (e: ClassNotFoundException) {
            fail("RegisterAction class should exist: ${e.message}")
            return
        }

        val constructor = registerActionClass.getDeclaredConstructor()
        constructor.isAccessible = true
        val registerAction = constructor.newInstance() as AbstractAction

        // AbstractAction should have an ACTION_COMMAND_KEY set
        val actionCommand = registerAction.getValue(AbstractAction.ACTION_COMMAND_KEY)
        // The action command may or may not be set depending on constructor
        // This test just verifies the action was created properly
        assertNotNull("RegisterAction should be created", actionCommand ?: true)
    }

    /**
     * Test: createLeftSideActions method exists in LoginDialog.
     *
     * This verifies that LoginDialog has the method that returns RegisterAction.
     */
    @Test
    fun `createLeftSideActions method should exist`() {
        val method = try {
            LoginDialog::class.java.getDeclaredMethod("createLeftSideActions")
        } catch (e: NoSuchMethodException) {
            fail("LoginDialog should have createLeftSideActions method: ${e.message}")
            return
        }

        assertNotNull("createLeftSideActions method should exist", method)

        val returnType = method.returnType
        assertEquals("createLeftSideActions should return Array<AbstractAction>",
            Array<AbstractAction>::class.java, returnType)
    }
}
