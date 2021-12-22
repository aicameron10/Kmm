package debijenkorf.features.modals.viewmodel.login

import android.app.Application
import android.content.res.Resources
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import com.example.kmmsharedmoduleframework.presentation.LoginValidation
import debijenkorf.features.modals.R
import debijenkorf.libraries.core.models.common.FlowAction
import debijenkorf.libraries.core.models.loyalty.LoyaltyCustomersResponse
import debijenkorf.libraries.core.models.loyalty.LoyaltyRequestAuth
import debijenkorf.module.datasource.service.repository.loyalty.LoyaltyRepository
import javax.inject.Inject

class LoginNewViewModel @Inject constructor(
    private val resources: Resources,
    private val loyaltyRepository: LoyaltyRepository,
    application: Application
) : AndroidViewModel(application) {

    var email: String? = null
    var password: String? = null
    var registerLogin: Boolean = false
    var flowAction: FlowAction? = null

    val loyaltyObservable: LiveData<LoyaltyCustomersResponse>
    val loyaltyRequest: MutableLiveData<LoyaltyRequestAuth> = MutableLiveData()

    val validationUtils = LoginValidation()

    init {
        loyaltyObservable = Transformations.switchMap(loyaltyRequest) { input ->
            loyaltyRepository.getLoyaltyCustomers(input)
        }
    }

    fun validateEmail(emailToValidate: String?): Boolean {
        return validationUtils.validateEmail(emailToValidate)
    }

    fun validateEmptyPassword(passwordToValidate: String?): Boolean {
        return validationUtils.validateEmptyPassword(passwordToValidate)
    }

    fun validateRegisterPassword(passwordToValidate: String?): Boolean {
        return validationUtils.validateRegisterPassword(passwordToValidate)
    }

    fun getErrorText(loginResponse: Int?): String {
        return when (loginResponse) {
            401 -> resources.getString(R.string.Generic_ErrorMessage_LoginRequestErrors_Unauthorized_COPY)
            429 -> resources.getString(R.string.Generic_ErrorMessage_LoginRequestErrors_TooManyRequests_COPY)
            405, 503 -> resources.getString(R.string.Generic_ErrorMessage_LoginRequestErrors_ServiceDown_COPY)
            else -> resources.getString(R.string.Generic_Error_COPY)
        }
    }
}
