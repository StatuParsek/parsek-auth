package co.statu.rule.auth.route.profile

import co.statu.parsek.annotation.Endpoint
import co.statu.parsek.model.*
import co.statu.rule.auth.AuthFieldManager
import co.statu.rule.auth.AuthPlugin
import co.statu.rule.auth.api.LoggedInApi
import co.statu.rule.auth.error.EmailNotAvailable
import co.statu.rule.auth.error.InvalidEmail
import co.statu.rule.auth.provider.AuthProvider
import co.statu.rule.database.DatabaseManager
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import org.apache.commons.validator.routines.EmailValidator

@Endpoint
class UpdateProfileAPI(
    private val authPlugin: AuthPlugin
) : LoggedInApi() {
    override val paths = listOf(Path("/profile", RouteType.PUT))

    private val databaseManager by lazy {
        authPlugin.pluginBeanContext.getBean(DatabaseManager::class.java)
    }

    private val authProvider by lazy {
        authPlugin.pluginBeanContext.getBean(AuthProvider::class.java)
    }

    private val authFieldManager by lazy {
        authPlugin.pluginBeanContext.getBean(AuthFieldManager::class.java)
    }

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .body(
                json(
                    objectSchema()
                        .optionalProperty("email", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val email = data.getString("email")

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val jdbcPool = databaseManager.getConnectionPool()

        val user = userDao.getById(userId, jdbcPool)!!

        validateInput(
            email,
            data,
            user.additionalFields
        )

        if (email != null && email != user.email) {
            val emailExists = userDao.isEmailExists(email, jdbcPool)

            if (emailExists) {
                throw Errors(mapOf("email" to EmailNotAvailable()))
            }
        }

        if (email != null) {
            user.email = email
        }

        val additionalFields = authFieldManager.getAdditionalFields(data)

        if (!additionalFields.isEmpty) {
            additionalFields.forEach { additionalField ->
                user.additionalFields.put(additionalField.key, additionalField.value)
            }
        }

        userDao.update(user, jdbcPool)

        return Successful()
    }

    private suspend fun validateInput(
        email: String?,
        data: JsonObject,
        additionalFields: JsonObject,
    ) {
        if (email != null && (email.isBlank() || !EmailValidator.getInstance().isValid(email))) {
            throw InvalidEmail()
        }

        authFieldManager.validateFields(data, additionalFields)
    }
}