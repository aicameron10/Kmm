package com.example.kmmsharedmoduleframework.presentation

class LoginValidation {
    fun validateEmail(emailToValidate: String?): Boolean {
        emailToValidate?.let { email ->
            email.trim()
            return if (email.isBlank()) {
                false
            } else isValidEmail(email)
        } ?: return false
    }

    fun validateEmptyPassword(passwordToValidate: String?): Boolean {
        passwordToValidate?.let { password ->
            password.trim()
            return password.isNotBlank()
        } ?: return false
    }

    fun validateRegisterPassword(passwordToValidate: String?): Boolean {
        passwordToValidate?.let { password ->
            password.trim()
            return if (password.isBlank()) {
                false
            } else isValidPassword(password)
        } ?: return false
    }

    private fun isValidEmail(email: String): Boolean {
        val emailPattern = (
            "[a-zA-Z0-9\\+\\.\\_\\%\\-\\+]{1,256}" +
                "\\@" +
                "[a-zA-Z0-9][a-zA-Z0-9\\-]{0,64}" +
                "(" +
                "\\." +
                "[a-zA-Z0-9][a-zA-Z0-9\\-]{0,25}" +
                ")"
            ).toRegex()
        return emailPattern.matches(email)
    }

    private fun isValidPassword(password: String): Boolean {
        val passwordPattern = ("^(?=.*?[A-Z])(?=.*?[a-z])(?=.*?[0-9]).{6,}\$").toRegex()

        return passwordPattern.matches(password)
    }
}
