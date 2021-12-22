package debijenkorf.features.modals.ui.fragment.login

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.text.SpannableString
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.findNavController
import com.example.kmmsharedmoduleframework.source.model.AuthenticateRequest
import com.example.kmmsharedmoduleframework.source.network.CustomerApi
import debijenkorf.features.modals.R
import debijenkorf.features.modals.databinding.FragmentNewloginBinding
import debijenkorf.features.modals.ui.fragment.common.CustomerFormFragmentDirections
import debijenkorf.features.modals.ui.fragment.loyalty.LoyaltyCardActivateFragmentDirections
import debijenkorf.features.modals.ui.fragment.loyalty.LoyaltyPrivilegeMemberSuccessFragmentDirections
import debijenkorf.features.modals.ui.fragment.loyalty.LoyaltyScanFragmentDirections
import debijenkorf.features.modals.viewmodel.ModalFlowActivityViewModel
import debijenkorf.features.modals.viewmodel.login.LoginNewViewModel
import debijenkorf.libraries.core.injection.Injectable
import debijenkorf.libraries.core.injection.ViewModelFactoryProvider
import debijenkorf.libraries.core.models.common.FlowAction
import debijenkorf.libraries.core.models.customer.request.ApiRequestAuth
import debijenkorf.libraries.core.utils.Constants
import debijenkorf.libraries.core.utils.SessionManager
import debijenkorf.libraries.core.utils.extensions.hideKeyboard
import debijenkorf.libraries.core.utils.extensions.setSafeOnClickListener
import debijenkorf.libraries.core.utils.locale.LocaleManager
import kotlinx.coroutines.*
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

class LoginNewFragment : androidx.fragment.app.Fragment(), Injectable, CoroutineScope {

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var sessionManager: SessionManager

    @Inject
    lateinit var localeManager: LocaleManager

    private lateinit var viewModel: LoginNewViewModel
    private lateinit var activityViewModel: ModalFlowActivityViewModel
    private lateinit var binding: FragmentNewloginBinding

    private val job = Job()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        setHasOptionsMenu(true)
        // Inflate this data binding layout
        binding = DataBindingUtil.inflate(
            inflater,
            R.layout.fragment_newlogin,
            container,
            false
        )
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this, viewModelFactory).get(LoginNewViewModel::class.java)

        activityViewModel = activity?.run {
            val factory: ViewModelProvider.Factory? =
                (this as? ViewModelFactoryProvider)?.getFactory()
            factory?.let {
                ViewModelProvider(requireActivity(), factory)
                    .get(ModalFlowActivityViewModel::class.java)
            }
        } ?: throw Exception(Constants.INVALID_ACTIVITY)

        observeViewModel()
        setupClickListeners()

        arguments?.let {
            viewModel.flowAction = LoginFragmentArgs.fromBundle(it).flowAction
        }
    }

    fun observeViewModel() {
        // Update the list when the data change
        viewModel.loyaltyObservable.observe(
            viewLifecycleOwner,
            {
                if (it == null) {
                    Log.e("Login", "Loyalty called failed")
                }
            }
        )

        activityViewModel.loadingError().observe(
            viewLifecycleOwner,
            { errorText ->
                binding.isLoading = false
                errorText?.let {
                    Log.d("error", it)
                }
            }
        )

        activityViewModel.networkCallsComplete().observe(
            viewLifecycleOwner,
            { callsComplete ->
                callsComplete?.let {

                    requireActivity().hideKeyboard()

                    when (viewModel.flowAction) {
                        FlowAction.CHECKOUT_LOGIN -> {
                            (context as debijenkorf.features.modals.ui.activity.ModalFlowActivity).closeActivity(
                                Intent(),
                                Activity.RESULT_OK
                            )
                        }
                        FlowAction.CONVERT_TO_PRIVILEGE_MEMBER -> {
                            val action =
                                LoyaltyCardActivateFragmentDirections.actionToLoyaltyCardActivateFragment(
                                    flowAction = FlowAction.CONVERT_TO_PRIVILEGE_MEMBER
                                )
                            view?.findNavController()?.navigate(action)
                        }
                        FlowAction.REGISTER_AND_SCAN_KLANTENPAS -> {
                            val action =
                                LoyaltyPrivilegeMemberSuccessFragmentDirections.actionToLoyaltyPrivilegeMemberSuccessFragment(
                                    flowAction = FlowAction.REGISTER_AND_SCAN_KLANTENPAS
                                )
                            view?.findNavController()?.navigate(action)
                        }
                        FlowAction.SCAN_KLANTENPAS -> {
                            val action =
                                LoyaltyScanFragmentDirections.actionToLoyaltyScanFragment(flowAction = FlowAction.SCAN_KLANTENPAS)
                            view?.findNavController()?.navigate(action)
                        }
                        else -> {
                            (context as debijenkorf.features.modals.ui.activity.ModalFlowActivity).closeActivity(
                                Intent(),
                                Activity.RESULT_OK
                            )
                        }
                    }
                }

                binding.isLoading = false
            }
        )
    }

    fun setupClickListeners() {

        binding.loginPasswordForgot.setOnClickListener {
            view?.findNavController()?.navigate(R.id.action_to_forgotPasswordFragment)
        }

        binding.loginBtn.setSafeOnClickListener {
            binding.loginContainer.visibility = View.VISIBLE
            binding.registerContainer.visibility = View.GONE
        }

        binding.registrationBtn.setSafeOnClickListener {
            binding.loginContainer.visibility = View.GONE
            binding.registerContainer.visibility = View.VISIBLE
        }

        binding.registerBtnCheckAccount.setSafeOnClickListener {
            val checkEmailRequest = AuthenticateRequest(
                sessionManager.auth,
                binding.registerInputEmail.text.toString(),
                null
            )
            CustomerApi().getCustomerEmail(
                checkEmailRequest,
                success = {
                    Handler(Looper.getMainLooper()).post {
                        processCheckAccountEmailRegister(it)
                    }
                },
                failure = {
                    Handler(Looper.getMainLooper()).post {
                        processCheckAccountEmailRegister(it)
                    }
                }
            )
        }

        binding.loginBtnCheckAccount.setSafeOnClickListener {
            if (validateCheckAccountEmail()) {
                binding.isLoading = true
                val checkEmailRequest = AuthenticateRequest(
                    sessionManager.auth,
                    binding.loginInputEmail.text.toString(),
                    null
                )

                CustomerApi().getCustomerEmail(
                    checkEmailRequest,
                    success = {
                        Handler(Looper.getMainLooper()).post {
                            processCheckAccountEmailLogin(it)
                        }
                    },
                    failure = {
                        Handler(Looper.getMainLooper()).post {
                            processCheckAccountEmailLogin(it)
                        }
                    }
                )
            }
        }
    }

    private fun validateCheckAccountEmail(): Boolean {
        val email = binding.loginInputEmail.text.toString()
        val isEmailValid = viewModel.validateEmail(email)

        if (isEmailValid) {
            binding.loginInputLayoutEmail.isErrorEnabled = false
        } else {
            requestFocus(binding.loginInputEmail)
            binding.loginInputLayoutEmail.error =
                getString(R.string.Generic_Validation_MandatoryEmail_COPY)
            binding.checkAccountContainer.visibility = View.VISIBLE
        }

        return isEmailValid
    }

    private fun validateRegisterAccountEmail(): Boolean {
        val email = binding.registerInputEmail.text.toString()
        val isEmailValid = viewModel.validateEmail(email)

        if (isEmailValid) {
            binding.registerInputLayoutEmail.isErrorEnabled = false
        } else {
            binding.registerInputLayoutEmail.error =
                getString(R.string.Generic_Validation_MandatoryEmail_COPY)
            requestFocus(binding.registerInputEmail)
        }

        return isEmailValid
    }

    private fun validateRegisterAccountPassword(): Boolean {
        val pass = binding.registerInputPassword.text.toString()
        val isPasswordValid = viewModel.validateRegisterPassword(pass)

        if (isPasswordValid) {
            binding.registerInputLayoutPassword.isErrorEnabled = false
        } else {
            binding.registerInputLayoutPassword.error =
                getString(R.string.LoginScreen_InputFields_Validation_WrongPasswordFormat_COPY)
            requestFocus(binding.registerInputPassword)
        }

        return isPasswordValid
    }

    private fun validateCheckAccountPassword(): Boolean {
        val pass = binding.loginInputPassword.text.toString()
        val isPasswordValid = viewModel.validateEmptyPassword(pass)

        if (isPasswordValid) {
            binding.loginInputLayoutPassword.isErrorEnabled = false
        } else {
            binding.loginInputLayoutPassword.error =
                getString(R.string.Generic_Validation_MandatoryPassword_COPY)
            requestFocus(binding.loginInputPassword)
        }
        return isPasswordValid
    }

    private fun processCheckAccountEmailLogin(checkEmailStatus: Int?) {
        binding.isLoading = false
        if (checkEmailStatus == 404) {
            requestFocus(binding.loginInputEmail)
            val noAccountFound =
                resources.getString(R.string.LoginScreen_InputFields_Validation_NoAccountFound_COPY)
            @Suppress("DEPRECATION")
            binding.loginInputLayoutEmail.error =
                SpannableString(Html.fromHtml(noAccountFound))
        } else if (checkEmailStatus == null) {
            requestFocus(binding.loginInputEmail)
            binding.loginInputLayoutEmail.error =
                resources.getString(R.string.Generic_Error_COPY)
        } else {
            if (validateCheckAccountEmail()) {
                if (validateCheckAccountPassword()) {
                    loginUser()
                }
            }
        }
    }

    private fun processCheckAccountEmailRegister(checkEmailStatus: Int?) {
        binding.isLoading = false
        if (checkEmailStatus == 404) {
            if (validateRegisterAccountEmail()) {
                if (validateRegisterAccountPassword()) {
                    val password = binding.registerInputPassword.text.toString()
                    val email = binding.registerInputEmail.text.toString()
                    startRegistrationFlow(email, password)
                }
            }
        } else if (checkEmailStatus == 204) {
            requestFocus(binding.registerInputEmail)
            val noAccountFound =
                resources.getString(R.string.LoginScreen_InputFields_Validation_NoAccountFound_COPY)
            @Suppress("DEPRECATION")
            binding.registerInputLayoutEmail.error =
                SpannableString(Html.fromHtml(noAccountFound))
        } else {
            if (validateRegisterAccountEmail()) {
                requestFocus(binding.loginInputEmail)
                binding.registerInputLayoutEmail.error =
                    resources.getString(R.string.Generic_Error_COPY)
            }
        }
    }

    // TODO do we still need this?
//    private fun loginUser(email: String, password: String) {
//        registerLogin = false
//        binding.isLoading = true
//
//        this.email = email
//        this.password = password
//
//        val passwordSend: String = Base64.encodeToString(password.toByteArray(), Base64.NO_WRAP)
//        val loginRequest = AuthenticateRequest(sessionManager.auth, email, passwordSend)
//        getAuthentication(loginRequest)
//    }

    private fun getAuthentication(authenticateRequest: AuthenticateRequest) {
        val customerApi = CustomerApi()

        launch(Dispatchers.IO) {
            customerApi.authenticate(
                authenticateRequest,
                success = { login ->
                    launch(Dispatchers.Main) {
                        binding.isError = false
                        if (login != null) {
                            login.let { loginResponse ->
                                binding.isLoading = false
                                loginResponse.data?.let { loginData ->
                                    val authToken = loginData.authenticationToken
                                    val customerId = loginData.customer.id ?: ""
                                    val customerData = loginData.customer

                                    activity?.let {
                                        viewModel.email?.let { email ->
                                            viewModel.password?.let { password ->
                                                activityViewModel.saveUser(
                                                    authToken, customerId, email,
                                                    password, customerData
                                                )
                                                getLoyaltyCustomer()
                                                activityViewModel.fetchPreferencesAndCreditCards(
                                                    ApiRequestAuth(
                                                        sessionManager.auth,
                                                        localeManager.getServerLocale()
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                failure = {
                    launch(Dispatchers.Main) {
                        if (it != null) {
                            if (viewModel.registerLogin) {
                                binding.isErrorRegister = true
                                binding.loginInputLayoutPassword.error =
                                    viewModel.getErrorText(it)
                            } else {
                                binding.isError = true
                                binding.loginInputLayoutPassword.error =
                                    viewModel.getErrorText(it)
                            }
                            binding.isLoading = false
                        }
                    }
                }
            )
        }

/*        launch(Dispatchers.Main) {
            withContext(Dispatchers.IO) {
                customerApi.authenticate(
                    authenticateRequest,
                    success = { login ->
                        binding.isError = false
                        if (login != null) {
                            login.let { loginResponse ->
                                if (loginResponse.error != null) {
                                    if (registerLogin) {
                                        binding.isErrorRegister = true
                                        binding.loginErrorContainerRegister.errorText.text =
                                            viewModel.getErrorText(loginResponse)
                                    } else {
                                        binding.isError = true
                                        binding.loginErrorContainer.errorText.text =
                                            viewModel.getErrorText(loginResponse)
                                    }
                                    binding.isLoading = false
                                } else {
                                    loginResponse.data?.let { loginData ->
                                        val authToken = loginData.authenticationToken
                                        val customerId = loginData.customer.id ?: ""
                                        val customerData = loginData.customer

                                        activity?.let {
                                            email?.let { email ->
                                                password?.let { password ->
                                                    activityViewModel.saveUser(
                                                        authToken, customerId, email,
                                                        password, customerData
                                                    )
                                                    getLoyaltyCustomer()
                                                    activityViewModel.fetchPreferencesAndCreditCards(
                                                        ApiRequestAuth(
                                                            sessionManager.auth,
                                                            localeManager.getServerLocale()
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    failure = {
                        binding.isLoading = false
                    }
                )
            }
        }*/
    }

    private fun loginUser() {
        viewModel.email = binding.loginInputEmail.text.toString().trim()
        viewModel.password = binding.loginInputPassword.text.toString().trim()

        viewModel.registerLogin = true

        viewModel.email?.let { email ->
            viewModel.password?.let { password ->
                binding.isLoading = true
                val passwordSend: String =
                    Base64.encodeToString(password.toByteArray(), Base64.NO_WRAP)
                val loginRequest = AuthenticateRequest(sessionManager.auth, email, passwordSend)
                getAuthentication(loginRequest)
            }
        }
    }

    private fun startRegistrationFlow(username: String, password: String) {
        // Redirect the user to register and scan a klantenpas if they started the flow
        // to scan a klantenpas but didn't have an account yet
        when (viewModel.flowAction) {
            FlowAction.SCAN_KLANTENPAS -> {
                viewModel.flowAction = FlowAction.REGISTER_AND_SCAN_KLANTENPAS
            }
            FlowAction.NONE -> {
                viewModel.flowAction = FlowAction.UPGRADE_TO_PRIVILEGE_MEMBER
            }
            FlowAction.CHECKOUT_LOGIN -> {
                viewModel.flowAction = FlowAction.CHECKOUT_REGISTER
            }
            else -> {
            }
        }

        val actionTo = CustomerFormFragmentDirections.actionToCustomerForm(
            flowAction = viewModel.flowAction
                ?: FlowAction.NONE,
            password = password, email = username
        )
        view?.findNavController()?.navigate(actionTo)
    }

    private fun getLoyaltyCustomer() {
        val request =
            debijenkorf.libraries.core.models.loyalty.LoyaltyRequestAuth(sessionManager.auth)
        viewModel.loyaltyRequest.value = request
    }

    private fun requestFocus(view: View) {
        if (view.requestFocus()) {
            activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
    }

    override val coroutineContext: CoroutineContext
        get() = job

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
