package debijenkorf.features.modals.ui.fragment.common

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.Html
import android.text.TextWatcher
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources.getDrawable
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.findNavController
import com.example.kmmsharedmoduleframework.source.model.Customer
import com.example.kmmsharedmoduleframework.source.model.CustomerAddress
import com.example.kmmsharedmoduleframework.source.model.CustomerInfo
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.wdullaer.materialdatetimepicker.date.DatePickerDialog
import debijenkorf.features.modals.R
import debijenkorf.features.modals.callback.ModalFlowCallback
import debijenkorf.features.modals.databinding.FragmentCustomerFormBinding
import debijenkorf.features.modals.ui.activity.ModalFlowActivity
import debijenkorf.features.modals.ui.fragment.loyalty.LoyaltyCardActivateFragmentDirections
import debijenkorf.features.modals.ui.fragment.loyalty.LoyaltyPrivilegeMemberSuccessFragmentDirections
import debijenkorf.features.modals.ui.fragment.loyalty.LoyaltyScanFragmentDirections
import debijenkorf.features.modals.viewmodel.ModalFlowActivityViewModel
import debijenkorf.features.modals.viewmodel.common.CustomerFormViewModel
import debijenkorf.libraries.analytics.TrackingHelper
import debijenkorf.libraries.analytics.tags.*
import debijenkorf.libraries.core.injection.Injectable
import debijenkorf.libraries.core.injection.ViewModelFactoryProvider
import debijenkorf.libraries.core.models.common.FlowAction
import debijenkorf.libraries.core.models.customer.request.*
import debijenkorf.libraries.core.models.customer.response.CustomerAddresses
import debijenkorf.libraries.core.models.customer.response.CustomerDataResponse
import debijenkorf.libraries.core.models.shipments.CompleteAddressRequest
import debijenkorf.libraries.core.utils.Constants
import debijenkorf.libraries.core.utils.SessionManager
import debijenkorf.libraries.core.utils.extensions.*
import debijenkorf.libraries.core.utils.locale.Country
import debijenkorf.libraries.core.utils.locale.FormatManager
import debijenkorf.libraries.core.utils.locale.LocaleManager
import debijenkorf.libraries.feature_toggle.FeatureFlag
import debijenkorf.libraries.feature_toggle.RuntimeBehavior
import debijenkorf.libraries.navigation.model.NavigationResult
import debijenkorf.module.datasource.service.repository.shipments.AddressValidation
import kotlinx.android.synthetic.main.fragment_loyalty_card_activate.view.*
import kotlinx.android.synthetic.main.fragment_loyalty_scan_choice.view.*
import java.util.*
import javax.inject.Inject

class CustomerFormFragment : androidx.fragment.app.Fragment(), Injectable, ValidationListener {

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var sessionManager: SessionManager

    @Inject
    lateinit var trackingHelper: TrackingHelper

    @Inject
    lateinit var localeManager: LocaleManager

    @Inject
    lateinit var formatManager: FormatManager

    @Inject
    lateinit var firebaseAnalytics: FirebaseCrashlytics

    @Inject
    lateinit var runtimeBehavior: RuntimeBehavior

    private lateinit var viewModel: CustomerFormViewModel
    private lateinit var activityViewModel: ModalFlowActivityViewModel
    private lateinit var binding: FragmentCustomerFormBinding

    private var addressRequiresValidation: Boolean = false
    private var validPromoCode: Boolean = false
    private var continueSubmit: Boolean = false
    private var countryCode: String? = null
    private var gender: GenderType = GenderType.UNKNOWN
    private var title: String = ""
    private var modalFlowCallback: ModalFlowCallback? = null
    private var flowAction: FlowAction? = null
    private var addressId: String = ""
    private var btnSave: TextView? = null
    private var currentAddressValidation: AddressValidationState? = null

    enum class GenderType(val key: String) {
        MALE("MALE"),
        FEMALE("FEMALE"),
        UNKNOWN("UNKNOWN")
    }

    private enum class AddressValidationState {
        SUCCESS,
        MATCH_FOUND,
        NETWORK_ERROR,
        USER_NO_SELECTION,
        USER_VERIFIED_SELECTION,
        USER_MANUAL_SELECTION
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        setHasOptionsMenu(true)
        // Inflate this data binding layout
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_customer_form, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this, viewModelFactory).get(CustomerFormViewModel::class.java)

        observeViewModel()

        countryCode = localeManager.getCountrySelection().key.toUpperCase(Locale.getDefault())

        modalFlowCallback = activity as ModalFlowCallback

        setTitle()

        // Check for country code to change visibility
        if (countryCode.equals(Country.NETHERLANDS.key, ignoreCase = true)) {
            binding.customerFormCityLayout.visibility = View.GONE
            binding.customerFormStreetLayout.visibility = View.GONE
            binding.customerFormTelephoneLayout.visibility = View.GONE
        } else if (flowAction == FlowAction.DELIVERY_ADDRESS_FORM) {
            binding.customerFormCityLayout.visibility = View.VISIBLE
            binding.customerFormStreetLayout.visibility = View.VISIBLE
            binding.customerFormTelephoneLayout.visibility = View.VISIBLE
        }

        binding.addressValidationComponent.addressValidationContainer.visibility = View.GONE

        arguments?.let {
            flowAction = CustomerFormFragmentArgs.fromBundle(it).flowAction

            viewModel.email = CustomerFormFragmentArgs.fromBundle(it).email
            viewModel.password = CustomerFormFragmentArgs.fromBundle(it).password

            // Edge case to upgrade to privilege member from more menu
            if (flowAction == FlowAction.CONVERT_TO_PRIVILEGE_MEMBER) {
                fetchCustomer()
            }

            when {
                flowAction == FlowAction.DELIVERY_ADDRESS_FORM || flowAction == FlowAction.DELIVERY_ADDRESS_FORM_NEW -> {
                    trackingHelper.clickOperation(
                        action = AnalyticsAction.VIEW,
                        category = AnalyticsCategory.ACCOUNT_FORM,
                        event = AnalyticsEvent.FORM,
                        label = AnalyticsLabel.PROFILE_EDIT_DELIVERY_ADDRESS.key
                    )
                    modalFlowCallback?.changeTitle(resources.getString(R.string.DeliveryAddressScreen_EditTitle_COPY))
                    modalFlowCallback?.toggleBackArrow(true)
                    modalFlowCallback?.toggleModalCloseButton(false)
                    btnSave = modalFlowCallback?.getSaveButton()
                    btnSave?.visibility = View.VISIBLE
                    binding.customerFormTypeSection.visibility = View.VISIBLE
                    binding.customerFormTelephoneLayout.visibility = View.VISIBLE
                    binding.customerFormLandLayout.isEnabled = false
                    countryDropDown()
                    if (flowAction == FlowAction.DELIVERY_ADDRESS_FORM) {
                        addressId = CustomerFormFragmentArgs.fromBundle(it).addressId ?: ""
                        binding.customerFormDeleteButton.visibility = View.VISIBLE
                        binding.customerFormCityLayout.visibility = View.VISIBLE
                        binding.customerFormStreetLayout.visibility = View.VISIBLE
                        getAddress()
                    } else {
                    }
                }
                flowAction == FlowAction.INVOICE_ADDRESS_FORM -> {
                    trackingHelper.clickOperation(
                        action = AnalyticsAction.VIEW,
                        category = AnalyticsCategory.ACCOUNT_FORM,
                        event = AnalyticsEvent.FORM,
                        label = AnalyticsLabel.PROFILE_EDIT_PAYMENT_ADDRESS.key
                    )

                    modalFlowCallback?.changeTitle(resources.getString(R.string.PersonalDetailsScreen_BillingAddress_COPY))
                    modalFlowCallback?.toggleBackArrow(true)
                    modalFlowCallback?.toggleModalCloseButton(false)
                    btnSave = modalFlowCallback?.getSaveButton()

                    btnSave?.visibility = View.VISIBLE
                    binding.customerFormTelephoneLayout.visibility = View.VISIBLE
                    fetchCustomer()
                }
                flowAction == FlowAction.CHECKOUT_INVOICE_ADDRESS_FORM -> {
                    trackingHelper.screenOperation(requireActivity(), AnalyticsScreen.CHECKOUT_ADDRESS)
                    binding.customerFormTelephoneLayout.visibility = View.VISIBLE
                    binding.customerFormCompanyLayout.visibility = View.VISIBLE
                    binding.customerFormSubmitButton.visibility = View.VISIBLE
                    countryDropDown()
                    binding.customerFormSubmitButton.text = resources.getString(R.string.Generic_SaveAndCloseButton_COPY)
                    modalFlowCallback?.changeTitle(resources.getString(R.string.PersonalDetailsScreen_BillingAddress_COPY))
                    modalFlowCallback?.toggleBackArrow(true)
                    modalFlowCallback?.toggleModalCloseButton(false)

                    fetchCustomer()
                }
                flowAction == FlowAction.CHECKOUT_DELIVERY_ADDRESS_FORM_UPDATE -> {
                    trackingHelper.screenOperation(requireActivity(), AnalyticsScreen.CHECKOUT_ADDRESS)
                    binding.customerFormTelephoneLayout.visibility = View.VISIBLE
                    binding.customerFormCompanyLayout.visibility = View.VISIBLE
                    binding.customerFormSubmitButton.visibility = View.VISIBLE
                    binding.customerFormSubmitButton.text = resources.getString(R.string.Generic_SaveAndCloseButton_COPY)
                    binding.customerFormDeleteButton.visibility = View.VISIBLE
                    binding.customerFormLandLayout.isEnabled = false
                    countryDropDown()
                    addressId = CustomerFormFragmentArgs.fromBundle(it).addressId ?: ""
                    modalFlowCallback?.changeTitle(resources.getString(R.string.OrderDetailsScreen_DeliveryComponent_Title_COPY))
                    modalFlowCallback?.toggleBackArrow(true)
                    modalFlowCallback?.toggleModalCloseButton(false)
                    getAddress()
                }
                flowAction == FlowAction.CHECKOUT_DELIVERY_ADDRESS_FORM -> {
                    trackingHelper.screenOperation(requireActivity(), AnalyticsScreen.CHECKOUT_ADDRESS)
                    binding.customerFormTelephoneLayout.visibility = View.VISIBLE
                    binding.customerFormCompanyLayout.visibility = View.VISIBLE
                    binding.customerFormSubmitButton.visibility = View.VISIBLE
                    binding.customerFormLandLayout.isEnabled = false
                    countryDropDown()
                    binding.customerFormSubmitButton.text = resources.getString(R.string.Generic_SaveAndCloseButton_COPY)
                    modalFlowCallback?.changeTitle(resources.getString(R.string.OrderDetailsScreen_DeliveryComponent_Title_COPY))
                    modalFlowCallback?.toggleBackArrow(true)
                    modalFlowCallback?.toggleModalCloseButton(false)
                }
                flowAction != FlowAction.DELIVERY_ADDRESS_FORM || flowAction != FlowAction.DELIVERY_ADDRESS_FORM_NEW -> {

                    binding.customerFormSubmitButton.visibility = View.VISIBLE
                    binding.customerFormBirthDateLayout.visibility = View.VISIBLE
                    binding.customerFormPromoSection.visibility = View.VISIBLE
                    binding.customerFormPrivacySection.visibility = View.VISIBLE
                    countryDropDown()

                    activityViewModel = activity?.run {
                        val factory: ViewModelProvider.Factory? = (this as? ViewModelFactoryProvider)?.getFactory()
                        factory?.let {
                            ViewModelProvider(requireActivity(), factory)
                                .get(ModalFlowActivityViewModel::class.java)
                        }
                    } ?: throw Exception(Constants.INVALID_ACTIVITY)

                    modalFlowCallback?.toggleBackArrow(true)
                    modalFlowCallback?.toggleModalCloseButton(false)
                }
                else -> {
                }
            }
        }
        setupListeners()
    }

    private fun countryDropDown() {
        when (localeManager.getCountrySelection().key) {
            Country.NETHERLANDS.key -> {
                countryCode = Country.NETHERLANDS.key.toUpperCase(Locale.getDefault())
                binding.customerFormLandInput.setText(getString(R.string.Generic_SupportedCountries_Netherlands_COPY))
            }
            Country.BELGIUM.key -> {
                countryCode = Country.BELGIUM.key.toUpperCase(Locale.getDefault())
                binding.customerFormBusLayout.visibility = View.VISIBLE
                binding.customerFormLandInput.setText(getString(R.string.Generic_SupportedCountries_Belgium_COPY))
            }
            Country.LUXEMBOURG.key -> {
                countryCode = Country.LUXEMBOURG.key.toUpperCase(Locale.getDefault())
                binding.customerFormBusLayout.visibility = View.VISIBLE
                binding.customerFormLandInput.setText(getString(R.string.Generic_SupportedCountries_Luxembourg_COPY))
            }
            Country.GERMANY.key -> {
                countryCode = Country.GERMANY.key.toUpperCase(Locale.getDefault())
                binding.customerFormLandInput.setText(getString(R.string.Generic_SupportedCountries_Germany_COPY))
            }
            Country.AUSTRIA.key -> {
                countryCode = Country.AUSTRIA.key.toUpperCase(Locale.getDefault())
                binding.customerFormLandInput.setText(getString(R.string.Generic_SupportedCountries_Austria_COPY))
            }
            Country.FRANCE.key -> {
                countryCode = Country.FRANCE.key.toUpperCase(Locale.getDefault())
                binding.customerFormLandInput.setText(getString(R.string.Generic_SupportedCountries_France_COPY))
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun setupListeners() {
        binding.customerFormSubmitButton.setSafeOnClickListener { submitForm() }

        activity?.let { context ->
            binding.customerFormInitialsInput.afterTextChanged {
                validateInitials(
                    activity = context,
                    input = binding.customerFormInitialsInput,
                    layout = binding.customerFormInitialsLayout,
                    scrollView = binding.customerFormScrollContainer,
                    performScroll = false
                )
            }

            binding.customerFormNameInput.afterTextChanged {
                validateFirstName(
                    activity = context,
                    input = binding.customerFormNameInput,
                    layout = binding.customerFormNameLayout,
                    scrollView = binding.customerFormScrollContainer,
                    performScroll = false
                )
            }

            binding.customerFormLastNameInput.afterTextChanged {
                validateLastName(
                    activity = context,
                    input = binding.customerFormLastNameInput,
                    layout = binding.customerFormLastNameLayout,
                    scrollView = binding.customerFormScrollContainer,
                    performScroll = false
                )
            }

            binding.customerFormBirthDateInput.afterTextChanged {
                validateDate(
                    activity = context,
                    input = binding.customerFormBirthDateInput,
                    layout = binding.customerFormBirthDateLayout,
                    scrollView = binding.customerFormScrollContainer,
                    performScroll = false
                )
            }

            binding.customerFormPostcodeInput.afterTextChanged {
                validatePostcode(
                    activity = context,
                    postCodeEditText = binding.customerFormPostcodeInput,
                    houseEditText = binding.customerFormHouseNumberInput,
                    streetEdit = binding.customerFormStreetInput,
                    cityEditText = binding.customerFormCityInput,
                    layout = binding.customerFormPostcodeLayout,
                    scrollView = binding.customerFormScrollContainer,
                    countryCode = countryCode ?: "",
                    listener = this,
                    performScroll = false,
                    localeManager = localeManager
                )
            }
            binding.customerFormHouseNumberInput.afterTextChanged {
                validateHouseNumber(
                    activity = context,
                    houseNumberEdit = binding.customerFormHouseNumberInput,
                    postCodeEdit = binding.customerFormPostcodeInput,
                    streetEdit = binding.customerFormStreetInput,
                    cityEditText = binding.customerFormCityInput,
                    layout = binding.customerFormHouseNumberLayout,
                    scrollView = binding.customerFormScrollContainer,
                    listener = this,
                    performScroll = false,
                    localeManager = localeManager
                )
            }

            binding.customerFormStreetInput.afterTextChanged {
                validateStreet(
                    activity = context,
                    input = binding.customerFormStreetInput,
                    layout = binding.customerFormStreetLayout,
                    scrollView = binding.customerFormScrollContainer,
                    addressChecker = addressRequiresValidation,
                    performScroll = false
                )
            }

            binding.customerFormCityInput.afterTextChanged {
                validateCity(
                    activity = context,
                    cityEditText = binding.customerFormCityInput,
                    layout = binding.customerFormCityLayout,
                    scrollView = binding.customerFormScrollContainer,
                    addressChecker = addressRequiresValidation,
                    performScroll = false,
                    houseNumberEditText = binding.customerFormHouseNumberInput,
                    streetEditText = binding.customerFormStreetInput,
                    postCodeEditText = binding.customerFormPostcodeInput,
                    listener = this,
                    localeManager = localeManager
                )
            }

            binding.customerFormTelephoneInput.afterTextChanged {
                validateTel(
                    activity = context,
                    countryCode = localeManager.getCountrySelection().key,
                    input = binding.customerFormTelephoneInput,
                    layout = binding.customerFormTelephoneLayout,
                    scrollView = binding.customerFormScrollContainer,
                    performScroll = false
                )
            }

            binding.customerFormToevoegInput.afterTextChanged {
                validateToevoeging(
                    activity = context,
                    input = binding.customerFormToevoegInput,
                    layout = binding.customerFormToevoegLayout,
                    scrollView = binding.customerFormScrollContainer,
                    performScroll = false
                )
            }

            binding.customerFormPromoCodeInput.afterTextChanged { binding.validatePromoCode.visibility = View.VISIBLE }
        }

        binding.customerFormMaleChip.textDetail.text = resources.getString(R.string.Generic_FormComponent_MrTitle_COPY)
        binding.customerFormFemaleChip.textDetail.text = resources.getString(R.string.Generic_FormComponent_MrsTitle_COPY)

        binding.customerFormMaleChip.chipContainer.setSafeOnClickListener {
            binding.customerFormTitleValidationLabel.visibility = View.GONE
            binding.customerFormMaleChip.chipContainer.background = getDrawable(requireContext(), R.drawable.chips_outline_selected)
            binding.customerFormMaleChip.textDetail.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
            binding.customerFormFemaleChip.chipContainer.background = getDrawable(requireContext(), R.drawable.chips_outline_unselected)
            binding.customerFormFemaleChip.textDetail.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
            gender = GenderType.MALE
        }

        binding.customerFormFemaleChip.chipContainer.setSafeOnClickListener {
            binding.customerFormTitleValidationLabel.visibility = View.GONE
            binding.customerFormFemaleChip.chipContainer.background = getDrawable(requireContext(), R.drawable.chips_outline_selected)
            binding.customerFormFemaleChip.textDetail.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
            binding.customerFormMaleChip.chipContainer.background = getDrawable(requireContext(), R.drawable.chips_outline_unselected)
            binding.customerFormMaleChip.textDetail.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
            gender = GenderType.FEMALE
        }

        btnSave?.setSafeOnClickListener {
            submitForm()
        }

        binding.customerFormDeleteButton.setSafeOnClickListener {
            showDeletePrompt()
        }

        val mBottomSheetDialog = context?.let { BottomSheetDialog(it) }
        val sheetView = layoutInflater.inflate(R.layout.country_select_bottom_sheet, null)
        mBottomSheetDialog?.setContentView(sheetView)
        mBottomSheetDialog?.setCancelable(false)
        mBottomSheetDialog?.setCanceledOnTouchOutside(true)

        val close = sheetView.findViewById<RelativeLayout>(R.id.close)
        val nlOption = sheetView.findViewById<TextView>(R.id.netherlands)
        val beOption = sheetView.findViewById<TextView>(R.id.belgium)
        val deOption = sheetView.findViewById<TextView>(R.id.germany)
        val atOption = sheetView.findViewById<TextView>(R.id.austria)
        val frOption = sheetView.findViewById<TextView>(R.id.france)
        val lxOption = sheetView.findViewById<TextView>(R.id.luxembourg)

        nlOption.text = getString(R.string.Generic_SupportedCountries_Netherlands_COPY)
        beOption.text = getString(R.string.Generic_SupportedCountries_Belgium_COPY)
        deOption.text = getString(R.string.Generic_SupportedCountries_Germany_COPY)
        atOption.text = getString(R.string.Generic_SupportedCountries_Austria_COPY)
        frOption.text = getString(R.string.Generic_SupportedCountries_France_COPY)
        lxOption.text = getString(R.string.Generic_SupportedCountries_Luxembourg_COPY)

        close.setOnClickListener {
            mBottomSheetDialog?.hide()
        }

        binding.customerFormLandInput.setOnClickListener {
            mBottomSheetDialog?.show()
        }

        nlOption.setOnClickListener {
            countryCode = Country.NETHERLANDS.key.toUpperCase(Locale.getDefault())
            binding.customerFormBusLayout.visibility = View.GONE
            binding.customerFormLandInput.setText(getString(R.string.Generic_SupportedCountries_Netherlands_COPY))
            mBottomSheetDialog?.hide()
        }

        beOption.setOnClickListener {
            countryCode = Country.BELGIUM.key.toUpperCase(Locale.getDefault())
            binding.customerFormBusLayout.visibility = View.VISIBLE
            binding.customerFormLandInput.setText(getString(R.string.Generic_SupportedCountries_Belgium_COPY))
            mBottomSheetDialog?.hide()
        }

        deOption.setOnClickListener {
            countryCode = Country.GERMANY.key.toUpperCase(Locale.getDefault())
            binding.customerFormBusLayout.visibility = View.GONE
            binding.customerFormLandInput.setText(getString(R.string.Generic_SupportedCountries_Germany_COPY))
            mBottomSheetDialog?.hide()
        }

        atOption.setOnClickListener {
            countryCode = Country.AUSTRIA.key.toUpperCase(Locale.getDefault())
            binding.customerFormBusLayout.visibility = View.GONE
            binding.customerFormLandInput.setText(getString(R.string.Generic_SupportedCountries_Austria_COPY))
            mBottomSheetDialog?.hide()
        }

        frOption.setOnClickListener {
            countryCode = Country.FRANCE.key.toUpperCase(Locale.getDefault())
            binding.customerFormBusLayout.visibility = View.GONE
            binding.customerFormLandInput.setText(getString(R.string.Generic_SupportedCountries_France_COPY))
            mBottomSheetDialog?.hide()
        }

        lxOption.setOnClickListener {
            countryCode = Country.LUXEMBOURG.key.toUpperCase(Locale.getDefault())
            binding.customerFormBusLayout.visibility = View.GONE
            binding.customerFormLandInput.setText(getString(R.string.Generic_SupportedCountries_Luxembourg_COPY))
            mBottomSheetDialog?.hide()
        }

        binding.customerFormNameInput.setupClearButtonWithAction()
        binding.customerFormTypeInput.setupClearButtonWithAction()
        binding.customerFormInitialsInput.setupClearButtonWithAction()
        binding.customerFormLastNameInput.setupClearButtonWithAction()
        binding.customerFormPostcodeInput.setupClearButtonWithAction()
        binding.customerFormCityInput.setupClearButtonWithAction()
        binding.customerFormHouseNumberInput.setupClearButtonWithAction()
        binding.customerFormToevoegInput.setupClearButtonWithAction()
        binding.customerFormStreetInput.setupClearButtonWithAction()
        binding.customerFormPromoCodeInput.setupClearButtonWithActionPromoCode()
        binding.customerFormTelephoneInput.setupClearButtonWithActionPromoCode()

        binding.customerFormBirthDateInput.setOnClickListener {
            val now = Calendar.getInstance()
            val dpd = DatePickerDialog.newInstance(
                { _, year, monthOfYear, dayOfMonth ->
                    binding.customerFormBirthDateInput.setText(
                        formatManager.calendarDate(
                            year = year,
                            monthOfYear = monthOfYear, dayOfMonth = dayOfMonth
                        )
                    )
                },
                now.get(Calendar.YEAR),
                now.get(Calendar.MONTH),
                now.get(Calendar.DAY_OF_MONTH)
            )
            dpd.dismissOnPause(true)
            dpd.maxDate = Calendar.getInstance()
            dpd.showYearPickerFirst(true)
            dpd.accentColor = ContextCompat.getColor(requireContext(), R.color.colorPrimary)
            dpd.show(requireActivity().supportFragmentManager, "DatePickerDialog")
        }

        binding.customerFormPrivacyLink.setOnClickListener {
            modalFlowCallback?.openGeneralWebview(resources.getString(R.string.DeBijenkorfScreen_PrivacyStatementTitle_COPY))
        }

        binding.validatePromoCode.setSafeOnClickListener {
            continueSubmit = false
            validatePromoCode(binding.customerFormPromoCodeInput.text.toString())
        }

        getPrivilegeMemberText()
    }

    private fun showDeletePrompt() {
        val alertDialogBuilder = AlertDialog.Builder(activity)
        alertDialogBuilder
            .setCancelable(true)
            .setTitle(getString(R.string.DeliveryAddressScreen_RemoveAddressButton_COPY))
            .setMessage(getString(R.string.DeliveryAddressScreen_RemoveAddressMessage_COPY))
            .setPositiveButton(getString(R.string.Generic_YesButton_COPY)) { dialog, _ ->
                deleteAddress()
                dialog.cancel()
            }
            .setNegativeButton(getString(R.string.Generic_NoButton_COPY)) { dialog, _ ->
                dialog.cancel()
            }

        val alertDialog = alertDialogBuilder.create()
        if (isVisible) {
            alertDialog.show()
        }
    }

    private fun getAddress() {
        val request = GetAddressDetailRequest(sessionManager.auth, addressId, localeManager.getServerLocale())
        viewModel.getAddressDetailRequest.value = request
    }

    private fun deleteAddress() {
        val request = DeleteAddressRequest(sessionManager.auth, addressId, localeManager.getServerLocale())
        viewModel.deleteAddressRequest.value = request
    }

    private fun observeViewModel() {
        viewModel.customerObservable.observe(
            viewLifecycleOwner,
            { response ->
                response?.data?.customer?.let { customer ->
                    customer.gender?.let {
                        gender = GenderType.valueOf(it)
                    }
                    customer.title?.let {
                        title = it
                    }
                    customer.lastName.let {
                        binding.customerFormLastNameInput.setText(it)
                    }
                    customer.firstName.let {
                        binding.customerFormNameInput.setText(it)
                    }
                    customer.birthday.let {
                        binding.customerFormBirthDateInput.setText(it)
                    }
                    customer.preposition.let {
                        binding.customerFormInitialsInput.setText(it)
                    }
                    if (customer.address != null) {
                        val country = localeManager.getCountryName(customer.address?.country ?: "")

                        binding.customerFormLandInput.setText(country)
                        countryCode = customer.address?.country ?: ""

                        customer.address?.postalCode.let {
                            binding.customerFormPostcodeInput.setText(it)
                        }
                        customer.address?.number.let {
                            binding.customerFormHouseNumberInput.setText(it)
                        }
                        customer.address?.streetName.let {
                            binding.customerFormStreetInput.setText(it)
                        }
                        customer.address?.cityName.let {
                            binding.customerFormCityInput.setText(it)
                        }
                        customer.address?.numberSuffix.let {
                            binding.customerFormToevoegInput.setText(it)
                        }
                        customer.address?.mobile.let {
                            binding.customerFormTelephoneInput.setText(it)
                        }
                    }

                    when (gender) {
                        GenderType.MALE -> {
                            gender = GenderType.MALE
                            binding.customerFormMaleChip.chipContainer.background = getDrawable(requireContext(), R.drawable.chips_outline_selected)
                            binding.customerFormMaleChip.textDetail.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
                        }
                        GenderType.FEMALE -> {
                            gender = GenderType.FEMALE
                            binding.customerFormFemaleChip.chipContainer.background = getDrawable(requireContext(), R.drawable.chips_outline_selected)
                            binding.customerFormFemaleChip.textDetail.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
                        }
                        else -> gender = GenderType.UNKNOWN
                    }
                }
            }
        )

        viewModel.getAddressObservable.observe(
            viewLifecycleOwner,
            {
                it?.let { response ->

                    response.data?.let { address ->

                        val country = localeManager.getCountryName(address.countryCode ?: "")

                        if (countryCode.equals(Country.BELGIUM.key, ignoreCase = true)) {
                            binding.customerFormBusLayout.visibility = View.VISIBLE
                        }
                        binding.customerFormLandInput.setText(country)
                        countryCode = address.countryCode?.toUpperCase(Locale.getDefault()) ?: ""

                        binding.customerFormTypeInput.setText(address.addressName)
                        binding.customerFormPostcodeInput.setText(address.postalCode)
                        binding.customerFormHouseNumberInput.setText(address.addressNr)
                        binding.customerFormToevoegInput.setText(address.street2)
                        binding.customerFormStreetInput.setText(address.street)
                        binding.customerFormCityInput.setText(address.city)
                        binding.customerFormBusInput.setText(address.street3)

                        binding.customerFormNameInput.setText(address.firstName ?: "")
                        binding.customerFormLastNameInput.setText(address.lastName ?: "")
                        binding.customerFormInitialsInput.setText(address.secondName ?: "")
                        binding.customerFormCityInput.setText(address.city)
                        binding.customerFormTelephoneInput.setText(address.mobile ?: "")

                        val titleValue = address.title ?: ""

                        if (titleValue.contains(resources.getString(R.string.Generic_FormComponent_MrTitle_COPY))) {
                            gender = GenderType.MALE
                            title = resources.getString(R.string.Generic_FormComponent_MrTitle_COPY)
                        } else {
                            gender = GenderType.FEMALE
                            title = resources.getString(R.string.Generic_FormComponent_MrsTitle_COPY)
                        }

                        when (gender) {
                            GenderType.MALE -> {
                                gender = GenderType.MALE
                                binding.customerFormMaleChip.chipContainer.background = getDrawable(requireContext(), R.drawable.chips_outline_selected)
                                binding.customerFormMaleChip.textDetail.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
                            }
                            GenderType.FEMALE -> {
                                gender = GenderType.FEMALE
                                binding.customerFormFemaleChip.chipContainer.background = getDrawable(requireContext(), R.drawable.chips_outline_selected)
                                binding.customerFormFemaleChip.textDetail.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
                            }
                            else -> gender = GenderType.UNKNOWN
                        }

                        countryCode = address.countryCode?.toUpperCase(Locale.getDefault()) ?: ""
                    }
                }
            }
        )

        viewModel.deleteAddressObservable.observe(
            viewLifecycleOwner,
            {
                it?.let {
                    context?.showToast(getString(R.string.DeliveryAddressScreen_RemoveAddressConfirmation_COPY))
                    val closingIntent = Intent()
                    closingIntent.putExtra(NavigationResult.ADDRESS_RESULT.key, FlowAction.CHECKOUT_DELIVERY_ADDRESS_FORM_UPDATE)
                    modalFlowCallback?.closeActivity(closingIntent, Activity.RESULT_OK)
                } ?: kotlin.run {
                    context?.showToast(getString(R.string.Generic_Error_COPY))
                }
            }
        )

        viewModel.customerSaveUserObservable.observe(
            viewLifecycleOwner,
            { customerResponse ->

                if (flowAction == FlowAction.INVOICE_ADDRESS_FORM || flowAction == FlowAction.CHECKOUT_INVOICE_ADDRESS_FORM) {
                    customerResponse.data?.let { loginData ->
                        trackingHelper.clickOperation(
                            action = AnalyticsAction.SAVE,
                            category = AnalyticsCategory.ACCOUNT_FORM,
                            event = AnalyticsEvent.FORM,
                            label = AnalyticsLabel.PROFILE_EDIT_PAYMENT_ADDRESS.key
                        )
                        btnSave?.visibility = View.GONE

                        val closingIntent = Intent()
                        loginData.customer.address?.let {
                            closingIntent.putExtra(NavigationResult.ADDRESS_RESULT.key, FlowAction.CHECKOUT_INVOICE_ADDRESS_FORM)
                        }
                        modalFlowCallback?.closeActivity(closingIntent, Activity.RESULT_OK)
                    } ?: kotlin.run {
                        customerResponse?.error?.let { error ->
                            binding.customerFormMessageBox.visibility = View.VISIBLE
                            binding.customerFormScrollContainer.scrollTo(0, binding.customerFormMessageBox.top)
                            binding.customerFormMessageBoxLabel.text = error.message
                        } ?: context?.showToast(getString(R.string.Generic_Error_COPY))
                    }
                } else {
                    customerResponse?.let {
                        processRegistration(it)
                    } ?: kotlin.run {
                        registrationFailure()
                    }
                }
            }
        )

        viewModel.customerRegisterUserObservable.observe(
            viewLifecycleOwner,
            {
                it?.let {
                    processRegistration(it)
                } ?: kotlin.run {
                    registrationFailure()
                }
            }
        )

        viewModel.customerPromoCodeObservable.observe(
            viewLifecycleOwner,
            {
                it?.let {
                    if (it.data?.valid == true) {
                        validPromoCode = true
                        binding.customerFormPromoCodeLayout.visibility = View.GONE
                        binding.validatePromoCode.visibility = View.GONE
                        binding.promoSuccessContainer.visibility = View.VISIBLE
                        binding.SuccessLayout.successTextView.text = resources.getString(R.string.RegistrationScreen_PromotionCodeComponent_ValidCode_COPY)
                        binding.successCode.text = binding.customerFormPromoCodeInput.text.toString()
                        binding.deletePromoCode.setOnClickListener {
                            binding.customerFormPromoCodeInput.text?.clear()
                            binding.successCode.text = ""
                            validPromoCode = false
                            binding.promoSuccessContainer.visibility = View.GONE
                            binding.validatePromoCode.visibility = View.GONE
                            binding.customerFormPromoCodeLayout.visibility = View.VISIBLE
                        }
                        if (continueSubmit) {
                            submitForm()
                        } else {
                            Log.d("promo code", "not form submit")
                        }
                    } else {
                        validatePromoCode(activity = requireActivity(), input = binding.customerFormPromoCodeInput, layout = binding.customerFormPromoCodeLayout, value = false, scrollView = binding.customerFormScrollContainer)
                    }
                } ?: kotlin.run {
                    validatePromoCode(activity = requireActivity(), input = binding.customerFormPromoCodeInput, layout = binding.customerFormPromoCodeLayout, value = false, scrollView = binding.customerFormScrollContainer)
                }
            }
        )

        viewModel.saveAddressObservable.observe(
            viewLifecycleOwner,
            { response ->
                binding.customerFormFragmentProgressBar.visibility = View.GONE
                response?.data?.let {
                    btnSave?.visibility = View.GONE
                    val closingIntent = Intent()
                    closingIntent.putExtra(NavigationResult.ADDRESS_RESULT.key, FlowAction.CHECKOUT_DELIVERY_ADDRESS_FORM)
                    modalFlowCallback?.closeActivity(closingIntent, Activity.RESULT_OK)
                } ?: kotlin.run {
                    if (!response?.error?.message.isNullOrBlank()) {
                        binding.customerFormMessageBox.visibility = View.VISIBLE
                        binding.customerFormScrollContainer.scrollTo(0, binding.customerFormMessageBox.top)
                        binding.customerFormMessageBoxLabel.text = response?.error?.message
                    }
                }
            }
        )

        viewModel.updateAddressObservable.observe(
            viewLifecycleOwner,
            { response ->
                binding.customerFormFragmentProgressBar.visibility = View.GONE
                response?.data?.let {
                    trackingHelper.clickOperation(
                        action = AnalyticsAction.SAVE,
                        category = AnalyticsCategory.ACCOUNT_FORM,
                        event = AnalyticsEvent.FORM,
                        label = AnalyticsLabel.PROFILE_EDIT_DELIVERY_ADDRESS.key
                    )
                    btnSave?.visibility = View.GONE

                    val closingIntent = Intent()
                    closingIntent.putExtra(NavigationResult.ADDRESS_RESULT.key, FlowAction.CHECKOUT_DELIVERY_ADDRESS_FORM_UPDATE)
                    modalFlowCallback?.closeActivity(closingIntent, Activity.RESULT_OK)
                } ?: kotlin.run {
                    response?.error?.message?.let { message ->
                        binding.customerFormMessageBox.visibility = View.VISIBLE
                        binding.customerFormScrollContainer.scrollTo(0, binding.customerFormMessageBox.top)
                        binding.customerFormMessageBoxLabel.text = message
                    } ?: context?.showToast(getString(R.string.Generic_Error_COPY))
                }
            }
        )

        viewModel.validAddressObservable.observe(
            viewLifecycleOwner,
            {
                viewModel.cachedAddressValidation = it
                it?.let { response ->
                    if (response.valid == true) {
                        if (response.score == 100) {
                            showAddressValidation(AddressValidationState.SUCCESS)
                        } else {
                            showAddressValidation(AddressValidationState.MATCH_FOUND)

                            binding.addressValidationComponent.addressEnteredRadio.setSafeOnClickListener {
                                showAddressValidation(AddressValidationState.USER_MANUAL_SELECTION)
                            }

                            binding.addressValidationComponent.addressValidationEnteredContainer.setSafeOnClickListener {
                                showAddressValidation(AddressValidationState.USER_MANUAL_SELECTION)
                            }

                            binding.addressValidationComponent.addressValidRadio.setSafeOnClickListener {
                                showAddressValidation(AddressValidationState.USER_VERIFIED_SELECTION)
                            }

                            binding.addressValidationComponent.addressValidationVerifiedContainer.setSafeOnClickListener {
                                showAddressValidation(AddressValidationState.USER_VERIFIED_SELECTION)
                            }
                        }
                    } else {
                        showAddressValidation(AddressValidationState.NETWORK_ERROR)
                    }
                } ?: kotlin.run {
                    showAddressValidation(AddressValidationState.NETWORK_ERROR)
                }
            }
        )

        viewModel.completeAddressObservable.observe(
            viewLifecycleOwner,
            {
                it?.let { response ->
                    if (!response.errorResponse) {
                        addressRequiresValidation = false

                        response.results?.getOrNull(0)?.let { result ->
                            binding.customerFormStreetInput.setText(result.street)
                            val addressString = "" + binding.customerFormStreetInput.text + " " + binding.customerFormHouseNumberInput.text + "," + "\n" + binding.customerFormPostcodeInput.text.toString().toUpperCase(Locale.getDefault()) + " " + result.city
                            binding.addressSuccesLayout.successContainer.visibility = View.VISIBLE
                            binding.addressSuccesLayout.successTextView.text = addressString
                            binding.addressWarningLayout.warningContainer.visibility = View.GONE
                            binding.customerFormCityLayout.visibility = View.GONE
                            binding.customerFormStreetLayout.visibility = View.GONE
                            binding.customerFormPostcodeLayout.error = null
                            binding.customerFormHouseNumberLayout.error = null
                        }
                        response.results?.getOrNull(0)?.city?.let { city ->
                            binding.customerFormCityInput.setText(city)
                        }
                    } else {
                        addressRequiresValidation = true
                    }
                } ?: kotlin.run {
                    binding.addressSuccesLayout.successContainer.visibility = View.GONE
                    binding.addressWarningLayout.warningContainer.visibility = View.VISIBLE
                    binding.customerFormPostcodeLayout.error = " "
                    binding.customerFormHouseNumberLayout.error = " "
                    val warningText = resources.getString(R.string.DeliveryAddressScreen_AddressWarningText_COPY)
                    val warning = resources.getString(R.string.DeliveryAddressScreen_AddressWarningTextContinue_COPY)
                    val warningString = "$warningText <br /> <u><b>$warning</b></u>"
                    @Suppress("DEPRECATION")
                    binding.addressWarningLayout.warningTextView.text = Html.fromHtml(warningString)
                    binding.addressWarningLayout.warningTextView.setOnClickListener {
                        binding.addressWarningLayout.warningTextView.text = warningText
                        binding.customerFormCityLayout.visibility = View.VISIBLE
                        binding.customerFormStreetLayout.visibility = View.VISIBLE
                        activity?.let { activity -> requestFocus(activity, binding.customerFormStreetInput) }
                    }
                    addressRequiresValidation = true
                }
            }
        )
    }

    private fun setTitle() {
        modalFlowCallback?.changeTitle(getString(R.string.RegistrationScreen_Title_COPY))
    }

    private fun getPrivilegeMemberText() {
        binding.customerFormPrivacyLink.text = resources.getString(R.string.RegistrationScreen_PMDisclaimer_Text_COPY)
    }

    private fun submitForm() {
        activity?.let {
            if (!validateAddressSelection()) return

            if (binding.customerFormTypeSection.visibility == View.VISIBLE) {
                if (!validateType(activity = requireActivity(), input = binding.customerFormTypeInput, layout = binding.customerFormTypeLayout, scrollView = binding.customerFormScrollContainer)) return
            }
            if (gender == GenderType.UNKNOWN) {
                binding.customerFormTitleValidationLabel.visibility = View.VISIBLE
                binding.customerFormScrollContainer.scrollTo(0, binding.customerFormTitleValidationLabel.top + 10)
                return
            } else {
                binding.customerFormTitleValidationLabel.visibility = View.GONE
            }
            if (!validateFirstName(
                    activity = it,
                    input = binding.customerFormNameInput,
                    layout = binding.customerFormNameLayout,
                    scrollView = binding.customerFormScrollContainer
                )
            ) return
            if (!validateInitials(
                    activity = it,
                    input = binding.customerFormInitialsInput,
                    layout = binding.customerFormInitialsLayout,
                    scrollView = binding.customerFormScrollContainer
                )
            ) return
            if (!validateLastName(
                    activity = it,
                    input = binding.customerFormLastNameInput,
                    layout = binding.customerFormLastNameLayout,
                    scrollView = binding.customerFormScrollContainer
                )
            ) return
            if (binding.customerFormBirthDateLayout.visibility == View.VISIBLE) {
                if (!validateDate(
                        activity = it,
                        input = binding.customerFormBirthDateInput,
                        layout = binding.customerFormBirthDateLayout,
                        scrollView = binding.customerFormScrollContainer
                    )
                ) return
            }
            if (!validatePostcode(
                    activity = it,
                    postCodeEditText = binding.customerFormPostcodeInput,
                    houseEditText = binding.customerFormHouseNumberInput,
                    streetEdit = binding.customerFormStreetInput,
                    cityEditText = binding.customerFormCityInput,
                    layout = binding.customerFormPostcodeLayout,
                    scrollView = binding.customerFormScrollContainer,
                    countryCode = countryCode
                        ?: "",
                    listener = this,
                    localeManager = localeManager
                )
            ) return

            if (!validateHouseNumber(
                    activity = it,
                    houseNumberEdit = binding.customerFormHouseNumberInput,
                    postCodeEdit = binding.customerFormPostcodeInput,
                    streetEdit = binding.customerFormStreetInput,
                    cityEditText = binding.customerFormCityInput,
                    layout = binding.customerFormHouseNumberLayout,
                    scrollView = binding.customerFormScrollContainer,
                    listener = this,
                    localeManager = localeManager
                )
            ) return
            if (!validateToevoeging(
                    activity = it,
                    input = binding.customerFormToevoegInput,
                    layout = binding.customerFormToevoegLayout,
                    scrollView = binding.customerFormScrollContainer
                )
            ) return
            if (!validateStreet(
                    activity = it,
                    input = binding.customerFormStreetInput,
                    layout = binding.customerFormStreetLayout,
                    scrollView = binding.customerFormScrollContainer,
                    addressChecker = addressRequiresValidation
                )
            ) return
            if (!validateCity(
                    activity = it,
                    cityEditText = binding.customerFormCityInput,
                    layout = binding.customerFormCityLayout,
                    scrollView = binding.customerFormScrollContainer,
                    addressChecker = addressRequiresValidation,
                    houseNumberEditText = binding.customerFormHouseNumberInput,
                    postCodeEditText = binding.customerFormPostcodeInput,
                    streetEditText = binding.customerFormStreetInput,
                    listener = this,
                    localeManager = localeManager
                )
            ) return

            if (binding.customerFormPromoSection.visibility == View.VISIBLE) {
                if (binding.customerFormPromoCodeInput.text.isNotEmpty() && !validPromoCode) {
                    validatePromoCode(binding.customerFormPromoCodeInput.text.toString())
                    continueSubmit = true
                    return
                }
            }

            view?.let { rootView ->
                context?.hideKeyboardFrom(rootView)
            }

            if (flowAction == FlowAction.DELIVERY_ADDRESS_FORM || flowAction == FlowAction.DELIVERY_ADDRESS_FORM_NEW) {
                if (flowAction == FlowAction.DELIVERY_ADDRESS_FORM_NEW) {
                    saveAddress()
                } else {
                    updateAddress()
                }
            } else if (flowAction == FlowAction.CHECKOUT_DELIVERY_ADDRESS_FORM_UPDATE || flowAction == FlowAction.CHECKOUT_DELIVERY_ADDRESS_FORM) {
                if (flowAction == FlowAction.CHECKOUT_DELIVERY_ADDRESS_FORM) {
                    saveAddress()
                } else {
                    updateAddress()
                }
            } else {
                submitPrivilegeForm()
            }
        }
    }

    private fun validateAddress(postCode: String?, houseNumber: String?, city: String?, street: String?) {
        val validAddress = AddressValidation(
            country = localeManager.getCountrySelection().key.toUpperCase(localeManager.userLocale),
            city = city,
            street = street,
            houseNumber = houseNumber,
            postCode = postCode
        )
        viewModel.getAddressValidationRequest.value = validAddress
    }

    private fun getCompleteAddress(postCode: String, houseNumber: String) {
        val addressRequest = CompleteAddressRequest(
            postCode, houseNumber,
            localeManager.getCountrySelection().key.toUpperCase(Locale.getDefault())
        )
        viewModel.getCompleteAddressRequest.value = addressRequest
    }

    private fun validatePromoCode(promoCode: String) {
        val request = PromoCodeRequest(promoCode, sessionManager.auth, localeManager.getServerLocale())
        viewModel.customerPromoCodeRequest.value = request
    }

    private fun fetchCustomer() {
        val request = ApiRequestAuth(sessionManager.auth, localeManager.getServerLocale())
        viewModel.customerRequest.value = request
    }

    private fun saveUser(customer: Customer) {
        val request = ApiRequestAuthCustomer(sessionManager.auth, customer, localeManager.getServerLocale())
        viewModel.customerSaveUserRequest.value = request
    }

    private fun registerUser(customer: Customer) {
        val request = ApiRequestAuthCustomer(sessionManager.auth, customer, localeManager.getServerLocale())
        viewModel.customerRegisterUserRequest.value = request
    }

    private fun updateAddress() {
        if (!validateAddressSelection()) return

        val address = generateAddress()

        if (gender == GenderType.MALE) {
            address.title = resources.getString(R.string.Generic_FormComponent_MrTitle_COPY)
        } else {
            address.title = resources.getString(R.string.Generic_FormComponent_MrsTitle_COPY)
        }
        address.countryCode = countryCode

        val request = UpdateAddressRequest(sessionManager.auth, addressId, address, localeManager.getServerLocale())
        viewModel.updateAddressRequest.value = request
    }

    private fun saveAddress() {
        if (!validateAddressSelection()) return

        val address = generateAddress()

        address.addressName = when (flowAction) {
            FlowAction.CHECKOUT_DELIVERY_ADDRESS_FORM -> getString(R.string.CheckoutScreen_DeliveryAddressComponent_Title_COPY)
            FlowAction.CHECKOUT_INVOICE_ADDRESS_FORM -> getString(R.string.CheckoutScreen_BillingAddressComponent_Title_COPY)
            else -> binding.customerFormTypeInput.text.toString()
        }

        if (gender == GenderType.MALE) {
            address.title = resources.getString(R.string.Generic_FormComponent_MrTitle_COPY)
        } else {
            address.title = resources.getString(R.string.Generic_FormComponent_MrsTitle_COPY)
        }
        address.countryCode = countryCode

        val request = SaveAddressRequest(sessionManager.auth, address, localeManager.getServerLocale())
        viewModel.saveAddressRequest.value = request
    }

    private fun validateAddressSelection(): Boolean {
        if (localeManager.getCountrySelection() != Country.NETHERLANDS && runtimeBehavior.isFeatureEnabled(FeatureFlag.ADDRESS_VALIDATION)) {
            if (currentAddressValidation == AddressValidationState.USER_NO_SELECTION) {
                binding.customerFormScrollContainer.smoothScrollTo(0, binding.addressValidationComponent.addressValidationContainer.bottom + 200)
                return false
            }

            if (!binding.addressValidationComponent.addressEnteredRadio.isChecked &&
                !binding.addressValidationComponent.addressValidRadio.isChecked &&
                binding.addressValidationComponent.addressValidationCorrection.visibility == View.VISIBLE
            ) {
                showAddressValidation(AddressValidationState.USER_NO_SELECTION)
                binding.customerFormScrollContainer.post {
                    binding.customerFormScrollContainer.smoothScrollTo(0, binding.addressValidationComponent.addressValidationContainer.bottom + 200)
                }
                return false
            }
        }
        return true
    }

    private fun generateAddress(): CustomerAddresses {
        val address = CustomerAddresses()
        if ((
            localeManager.getCountrySelection() == Country.NETHERLANDS ||
                currentAddressValidation in listOf(AddressValidationState.USER_MANUAL_SELECTION, AddressValidationState.NETWORK_ERROR) ||
                !runtimeBehavior.isFeatureEnabled(FeatureFlag.ADDRESS_VALIDATION)
            )
        ) {
            address.addressNr = binding.customerFormHouseNumberInput.text.toString()
            address.city = binding.customerFormCityInput.text.toString()
            address.firstName = binding.customerFormNameInput.text.toString()
            address.lastName = binding.customerFormLastNameInput.text.toString()
            address.secondName = binding.customerFormInitialsInput.text.toString()
            address.postalCode = binding.customerFormPostcodeInput.text.toString()
            address.street = binding.customerFormStreetInput.text.toString()
            address.street2 = binding.customerFormToevoegInput.text.toString()
            address.street3 = binding.customerFormBusInput.text.toString()
            address.company = binding.customerFormCompanyInput.text.toString()
            address.mobile = binding.customerFormTelephoneInput.text.toString()
        } else {
            val response = viewModel.cachedAddressValidation

            address.addressNr = response?.houseNumber
            address.city = response?.city
            address.firstName = binding.customerFormNameInput.text.toString()
            address.lastName = binding.customerFormLastNameInput.text.toString()
            address.secondName = binding.customerFormInitialsInput.text.toString()
            address.postalCode = response?.postCode
            address.street = response?.street
            address.company = binding.customerFormCompanyInput.text.toString()
            address.mobile = binding.customerFormTelephoneInput.text.toString()
        }
        return address
    }

    private fun completeRegistration() {

        when (flowAction) {
            FlowAction.CONVERT_TO_PRIVILEGE_MEMBER -> {
                trackingHelper.clickOperation(
                    AnalyticsEvent.FORM,
                    AnalyticsAction.END,
                    AnalyticsCategory.FORM_PRIVILEGE,
                    AnalyticsLabel.PRIVILEGE_MEMBER_FORM_SUCCESS.key
                )
                if (validPromoCode) {
                    sessionManager.registerPromoCode = true
                    val action = LoyaltyCardActivateFragmentDirections.actionToLoyaltyCardActivateFragment(flowAction = FlowAction.CONVERT_TO_PRIVILEGE_MEMBER_PROMO_CODE)
                    view?.findNavController()?.navigate(action)
                } else {
                    val action = LoyaltyCardActivateFragmentDirections.actionToLoyaltyCardActivateFragment(flowAction = FlowAction.CONVERT_TO_PRIVILEGE_MEMBER)
                    view?.findNavController()?.navigate(action)
                }
            }
            FlowAction.UPGRADE_TO_PRIVILEGE_MEMBER -> {
                trackingHelper.clickOperation(
                    AnalyticsEvent.FORM,
                    AnalyticsAction.END,
                    AnalyticsCategory.FORM_PRIVILEGE,
                    AnalyticsLabel.PRIVILEGE_MEMBER_FORM_SUCCESS.key
                )
                if (validPromoCode) {
                    sessionManager.registerPromoCode = true
                    if (localeManager.getCountrySelection() == Country.NETHERLANDS || localeManager.getCountrySelection() == Country.BELGIUM) {
                        val action = LoyaltyCardActivateFragmentDirections.actionToLoyaltyCardActivateFragment(flowAction = FlowAction.CONVERT_TO_PRIVILEGE_MEMBER_PROMO_CODE)
                        view?.findNavController()?.navigate(action)
                    } else {
                        val action = LoyaltyPrivilegeMemberSuccessFragmentDirections.actionToLoyaltyPrivilegeMemberSuccessFragment(flowAction = FlowAction.REGISTER)
                        view?.findNavController()?.navigate(action)
                    }
                } else {
                    if (localeManager.getCountrySelection() == Country.NETHERLANDS || localeManager.getCountrySelection() == Country.BELGIUM) {
                        val action = LoyaltyCardActivateFragmentDirections.actionToLoyaltyCardActivateFragment(flowAction = FlowAction.CONVERT_TO_PRIVILEGE_MEMBER)
                        view?.findNavController()?.navigate(action)
                    } else {
                        val action = LoyaltyPrivilegeMemberSuccessFragmentDirections.actionToLoyaltyPrivilegeMemberSuccessFragment(flowAction = FlowAction.REGISTER)
                        view?.findNavController()?.navigate(action)
                    }
                }
            }
            FlowAction.REGISTER_AND_SCAN_KLANTENPAS -> {
                if (validPromoCode) {
                    sessionManager.registerPromoCode = true
                    if (localeManager.getCountrySelection() in listOf(Country.NETHERLANDS, Country.BELGIUM, Country.LUXEMBOURG)) {
                        val action = LoyaltyScanFragmentDirections.actionToLoyaltyScanFragment(flowAction = FlowAction.REGISTER_AND_SCAN_KLANTENPAS_PROMO_CODE)
                        view?.findNavController()?.navigate(action)
                    } else {
                        val action = LoyaltyPrivilegeMemberSuccessFragmentDirections.actionToLoyaltyPrivilegeMemberSuccessFragment(flowAction = FlowAction.REGISTER_PROMO_CODE)
                        view?.findNavController()?.navigate(action)
                    }
                } else {
                    if (localeManager.getCountrySelection() in listOf(Country.NETHERLANDS, Country.BELGIUM, Country.LUXEMBOURG)) {
                        val action = LoyaltyScanFragmentDirections.actionToLoyaltyScanFragment(flowAction = FlowAction.REGISTER_AND_SCAN_KLANTENPAS)
                        view?.findNavController()?.navigate(action)
                    } else {
                        val action = LoyaltyPrivilegeMemberSuccessFragmentDirections.actionToLoyaltyPrivilegeMemberSuccessFragment(flowAction = FlowAction.REGISTER)
                        view?.findNavController()?.navigate(action)
                    }
                }
            }
            FlowAction.SCAN_KLANTENPAS -> {
                val action = LoyaltyScanFragmentDirections.actionToLoyaltyScanFragment(flowAction = FlowAction.SCAN_KLANTENPAS)
                view?.findNavController()?.navigate(action)
            }
            FlowAction.CHECKOUT_REGISTER -> {
                val action = LoyaltyPrivilegeMemberSuccessFragmentDirections.actionToLoyaltyPrivilegeMemberSuccessFragment(flowAction = FlowAction.CHECKOUT_REGISTER)
                view?.findNavController()?.navigate(action)
            }
            FlowAction.REGISTER -> {
                if (validPromoCode) {
                    sessionManager.registerPromoCode = true
                    val action = LoyaltyPrivilegeMemberSuccessFragmentDirections.actionToLoyaltyPrivilegeMemberSuccessFragment(flowAction = FlowAction.REGISTER_PROMO_CODE)
                    view?.findNavController()?.navigate(action)
                } else {
                    val action = LoyaltyPrivilegeMemberSuccessFragmentDirections.actionToLoyaltyPrivilegeMemberSuccessFragment(flowAction = FlowAction.REGISTER)
                    view?.findNavController()?.navigate(action)
                }
            }
            else -> {
                (context as? ModalFlowActivity)?.closeActivity(Intent(), Activity.RESULT_OK)
            }
        }
    }

    private fun submitPrivilegeForm() {
        binding.customerFormFragmentProgressBar.visibility = View.VISIBLE

        val pass = viewModel.password ?: ""

        val passwordSend = Base64.encodeToString(pass.toByteArray(), Base64.NO_WRAP)

        val customer = Customer()
        val customerInfo = CustomerInfo()
        val customerAddress = CustomerAddress()

        customerAddress.postalCode = binding.customerFormPostcodeInput.text.toString().trim()
        customerAddress.streetName = binding.customerFormStreetInput.text.toString().trim()
        customerAddress.number = binding.customerFormHouseNumberInput.text.toString().trim()
        customerAddress.cityName = binding.customerFormCityInput.text.toString().trim()
        customerAddress.numberSuffix = binding.customerFormToevoegInput.text.toString().trim()
        customerAddress.bus = binding.customerFormBusInput.text.toString().trim()
        customerAddress.company = binding.customerFormCompanyInput.text.toString().trim()
        customerAddress.country = countryCode

        if (sessionManager.isLoggedIn) {
            customerAddress.mobile = binding.customerFormTelephoneInput.text.toString().trim()
        }

        customerInfo.address = customerAddress
        customerInfo.preposition = binding.customerFormInitialsInput.text.toString().trim()
        customerInfo.lastName = binding.customerFormLastNameInput.text.toString().trim()
        customerInfo.gender = gender.key
        customerInfo.title = title
        customerInfo.firstName = binding.customerFormNameInput.text.toString().trim()
        customerInfo.birthday = binding.customerFormBirthDateInput.text.toString().trim()

        if (flowAction != FlowAction.INVOICE_ADDRESS_FORM || flowAction != FlowAction.CHECKOUT_INVOICE_ADDRESS_FORM) {
            customerInfo.birthday = binding.customerFormBirthDateInput.text.toString().trim()
            customerInfo.email = viewModel.email ?: ""

            customer.privilegeMember = true
            customer.promotionCode = binding.successCode.text.toString()
            customer.backgroundProfiling = true
            customer.base64EncodedPassword = passwordSend
        }

        customer.customer = customerInfo

        if (sessionManager.isLoggedIn) {
            saveUser(customer)
        } else {
            registerUser(customer)
        }
    }

    private fun showAddressValidation(currentValidationState: AddressValidationState) {
        val error100Color = ContextCompat.getColor(requireContext(), R.color.error100)
        val whiteColor = ContextCompat.getColor(requireContext(), R.color.white)

        val rectRedNoTop = ContextCompat.getDrawable(requireContext(), R.drawable.rect_red_200_outline_no_top)
        val rectRed = ContextCompat.getDrawable(requireContext(), R.drawable.rect_red_200_outline)

        val rectGreyNoTop = ContextCompat.getDrawable(requireContext(), R.drawable.rect_grey_300_outline_no_top)
        val rectGrey = ContextCompat.getDrawable(requireContext(), R.drawable.rect_grey_300_outline)

        val rectWarningNoTop = ContextCompat.getDrawable(requireContext(), R.drawable.rect_warning_100_selected_no_top)
        val rectGreen = ContextCompat.getDrawable(requireContext(), R.drawable.rect_success_100_solid)

        val response = viewModel.cachedAddressValidation
        val responseAddress = "${response?.street} ${response?.houseNumber},\n${response?.postCode} ${response?.city}"

        this.currentAddressValidation = currentValidationState

        binding.addressValidationComponent.addressValidationContainer.visibility = View.VISIBLE

        when (currentValidationState) {
            AddressValidationState.SUCCESS -> {
                binding.addressValidationComponent.addressValidationSuccess.successContainer.visibility = View.VISIBLE
                binding.addressValidationComponent.addressValidationCorrection.visibility = View.GONE
                binding.addressValidationComponent.addressValidationFailure.errorContainer.visibility = View.GONE

                val addressString = "${binding.customerFormStreetInput.text} ${binding.customerFormHouseNumberInput.text},\n${binding.customerFormPostcodeInput.text.toString().toUpperCase(Locale.getDefault())} ${response?.city}"
                binding.addressValidationComponent.addressValidationSuccess.successText = addressString

                binding.customerFormPostcodeLayout.error = null
                binding.customerFormHouseNumberLayout.error = null

                addressRequiresValidation = false
            }
            AddressValidationState.MATCH_FOUND -> {
                binding.addressValidationComponent.addressValidationSuccess.successContainer.visibility = View.GONE
                binding.addressValidationComponent.addressValidationCorrection.visibility = View.VISIBLE
                binding.addressValidationComponent.addressValidationFailure.errorContainer.visibility = View.GONE

                binding.addressValidationComponent.addressValidationSelectAddress.errorContainer.setBackgroundColor(error100Color)
                binding.addressValidationComponent.addressValidationSelectAddress.errorTextTitle.text = getString(R.string.Generic_Validation_AddressVerifiedTitle_COPY)
                binding.addressValidationComponent.addressValidationSelectAddress.errorTextDescription.text = getString(R.string.Generic_Validation_AddressVerifiedDescription_COPY)

                binding.addressValidationComponent.addressValidationVerifiedContainer.background = rectGrey
                binding.addressValidationComponent.addressValidationEnteredContainer.background = rectGreyNoTop

                binding.addressValidationComponent.addressValid.visibility = View.VISIBLE
                binding.addressValidationComponent.addressValid.text = responseAddress
                binding.addressValidationComponent.addressEntered.visibility = View.GONE

                binding.addressValidationComponent.addressSelectionText.visibility = View.GONE

                addressRequiresValidation = true
            }
            AddressValidationState.NETWORK_ERROR -> {
                binding.addressValidationComponent.addressValidationSuccess.successContainer.visibility = View.GONE
                binding.addressValidationComponent.addressValidationCorrection.visibility = View.GONE
                binding.addressValidationComponent.addressValidationFailure.errorContainer.visibility = View.VISIBLE

                binding.addressValidationComponent.addressValidationFailure.errorTextTitle.text = getString(R.string.Generic_Validation_AddressVerifiedTitle_COPY)
                binding.addressValidationComponent.addressValidationFailure.errorTextDescription.text = getString(R.string.Generic_Validation_AddressNoMatchDescription_COPY)
                binding.addressValidationComponent.addressValidationFailure.errorContainer.setBackgroundColor(error100Color)

                addressRequiresValidation = false
            }
            AddressValidationState.USER_NO_SELECTION -> {
                binding.addressValidationComponent.addressValidationSuccess.successContainer.visibility = View.GONE
                binding.addressValidationComponent.addressValidationCorrection.visibility = View.VISIBLE
                binding.addressValidationComponent.addressValidationFailure.errorContainer.visibility = View.GONE

                binding.addressValidationComponent.addressValidationSelectAddress.errorContainer.setBackgroundColor(error100Color)
                binding.addressValidationComponent.addressValidationSelectAddress.errorTextTitle.text = getString(R.string.Generic_Validation_AddressVerifiedTitle_COPY)
                binding.addressValidationComponent.addressValidationSelectAddress.errorTextDescription.text = getString(R.string.Generic_Validation_AddressVerifiedDescription_COPY)

                binding.addressValidationComponent.addressValidationVerifiedContainer.background = rectRed
                binding.addressValidationComponent.addressValidationEnteredContainer.background = rectRedNoTop

                binding.addressValidationComponent.addressSelectionText.visibility = View.VISIBLE
            }
            AddressValidationState.USER_VERIFIED_SELECTION -> {
                binding.addressValidationComponent.addressValidationSuccess.successContainer.visibility = View.GONE
                binding.addressValidationComponent.addressValidationCorrection.visibility = View.VISIBLE
                binding.addressValidationComponent.addressValidationFailure.errorContainer.visibility = View.GONE

                binding.addressValidationComponent.addressValidationVerifiedContainer.background = rectGreen
                binding.addressValidationComponent.addressValidationEnteredContainer.background = rectGreyNoTop

                binding.addressValidationComponent.addressValidationSelectAddress.errorContainer.setBackgroundColor(whiteColor)
                binding.addressValidationComponent.addressValidationSelectAddress.errorTextTitle.text = getString(R.string.Generic_Validation_AddressVerifiedTitle_COPY)
                binding.addressValidationComponent.addressValidationSelectAddress.errorTextDescription.text = getString(R.string.Generic_Validation_AddressVerifiedDescription_COPY)

                binding.addressValidationComponent.addressValid.visibility = View.VISIBLE
                binding.addressValidationComponent.addressValid.text = responseAddress
                binding.addressValidationComponent.addressEntered.visibility = View.GONE

                binding.addressValidationComponent.addressSelectionText.visibility = View.GONE

                binding.addressValidationComponent.addressEnteredRadio.isChecked = false
                binding.addressValidationComponent.addressValidRadio.isChecked = true
            }
            AddressValidationState.USER_MANUAL_SELECTION -> {
                binding.addressValidationComponent.addressValidationSuccess.successContainer.visibility = View.GONE
                binding.addressValidationComponent.addressValidationCorrection.visibility = View.VISIBLE
                binding.addressValidationComponent.addressValidationFailure.errorContainer.visibility = View.GONE

                binding.addressValidationComponent.addressValidationVerifiedContainer.background = rectGrey
                binding.addressValidationComponent.addressValidationEnteredContainer.background = rectWarningNoTop

                binding.addressValidationComponent.addressValidationSelectAddress.errorContainer.setBackgroundColor(whiteColor)
                binding.addressValidationComponent.addressValidationSelectAddress.errorTextTitle.text = getString(R.string.Generic_Validation_AddressVerifiedTitle_COPY)
                binding.addressValidationComponent.addressValidationSelectAddress.errorTextDescription.text = getString(R.string.Generic_Validation_AddressVerifiedDescription_COPY)

                val inputAddress = "${binding.customerFormStreetInput.text} ${binding.customerFormHouseNumberInput.text},\n${binding.customerFormPostcodeInput.text.toString().toUpperCase(Locale.getDefault())} ${binding.customerFormCityInput.text}"

                binding.addressValidationComponent.addressValid.visibility = View.GONE
                binding.addressValidationComponent.addressEntered.visibility = View.VISIBLE
                binding.addressValidationComponent.addressEntered.text = inputAddress

                binding.addressValidationComponent.addressSelectionText.visibility = View.GONE

                binding.addressValidationComponent.addressEnteredRadio.isChecked = true
                binding.addressValidationComponent.addressValidRadio.isChecked = false
            }
        }
    }

    /**
     * clears content of editText of PromoCode
     */
    private fun EditText.setupClearButtonWithActionPromoCode() {

        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(editable: Editable?) {
                val clearIcon = if (editable?.isNotEmpty() == true) R.drawable.icon_fail else 0
                setCompoundDrawablesWithIntrinsicBounds(0, 0, clearIcon, 0)
                if (editable?.isEmpty() == true) {
                    validatePromoCode(
                        activity = requireActivity(),
                        input = binding.customerFormPromoCodeInput,
                        layout = binding.customerFormPromoCodeLayout, value = true,
                        scrollView = binding.customerFormScrollContainer
                    )
                    binding.validatePromoCode.visibility = View.GONE
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

        setOnTouchListener(
            View.OnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    v.performClick()
                    if (event.rawX >= (this.right - this.compoundPaddingRight)) {
                        this.setText("")
                        validatePromoCode(
                            activity = requireActivity(),
                            input = binding.customerFormPromoCodeInput,
                            layout = binding.customerFormPromoCodeLayout,
                            value = true,
                            scrollView = binding.customerFormScrollContainer
                        )
                        binding.validatePromoCode.visibility = View.GONE
                        return@OnTouchListener true
                    }
                }
                return@OnTouchListener false
            }
        )
    }

    private fun processRegistration(response: CustomerDataResponse) {
        binding.customerFormFragmentProgressBar.visibility = View.GONE
        response.data.let { customer ->
            val authToken = customer?.authenticationToken
            val customerId = customer?.customer?.id
            val customerData = customer?.customer

            authToken?.let {
                customerData?.let {
                    sessionManager.createMember()

                    activityViewModel.saveUser(
                        authToken = authToken,
                        customerId = customerId ?: "", email = viewModel.email ?: "",
                        password = viewModel.password ?: "", customerData = customerData
                    )

                    completeRegistration()
                } ?: context?.showToast(getString(R.string.Generic_Error_COPY))
            } ?: context?.showToast(getString(R.string.Generic_Error_COPY))
        }
    }

    private fun registrationFailure() {
        binding.customerFormFragmentProgressBar.visibility = View.GONE
        binding.customerFormMessageBox.visibility = View.VISIBLE
        binding.customerFormMessageBoxLabel.text = resources.getString(R.string.RegistrationScreen_AccountExistsError_COPY)
        binding.customerFormMessageBox.let {
            binding.customerFormScrollContainer.scrollTo(0, binding.customerFormMessageBox.top + 10)
        }
        trackingHelper.clickOperation(
            AnalyticsEvent.FORM, AnalyticsAction.END,
            AnalyticsCategory.FORM_PRIVILEGE,
            AnalyticsLabel.PRIVILEGE_MEMBER_FORM_FAILURE.key
        )
    }

    override fun onResume() {
        super.onResume()
        activity?.let {
            it.dismissKeyboard(it.window)
        }
    }

    override fun onStop() {
        super.onStop()
        requireActivity().hideKeyboard()
        modalFlowCallback?.toggleBackArrow(false)
        modalFlowCallback?.toggleModalCloseButton(true)
    }

    override fun onCompleteAddress(postalCode: String, houseNumber: String) {
        getCompleteAddress(postalCode, houseNumber)
    }

    override fun onValidateAddress(postalCode: String?, houseNumber: String?, city: String?, street: String?) {
        if (runtimeBehavior.isFeatureEnabled(FeatureFlag.ADDRESS_VALIDATION)) {
            validateAddress(postalCode, houseNumber, city, street)
        }
    }
}
