package com.base.hilt.base

import android.accounts.NetworkErrorException
import com.apollographql.apollo3.api.ApolloResponse
import com.base.hilt.network.*
import com.base.hilt.utils.Constants.JSON
import com.base.hilt.utils.DebugLog
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import okhttp3.internal.http2.ConnectionShutdownException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import com.apollographql.apollo3.api.Operation
import com.apollographql.apollo3.exception.ApolloHttpException
import com.apollographql.apollo3.exception.ApolloNetworkException
import com.base.hilt.utils.Constants


/**
 * Common class for API calling
 */

open class BaseRepository {

    /**
     * This is the Base suspended method which is used for making the call of an Api and
     * Manage the Response with response code to display specific response message or code.
     * @param call ApiInterface method defination to make a call and get response from generic Area.
     */
    suspend fun <T : Any> makeAPICall(call: suspend () -> Response<ResponseData<T>>): ResponseHandler<ResponseData<T>?> {
        return withContext(Dispatchers.IO) {
            try {
                val response = call.invoke()
                when {
                    response.code() in (200..300) -> {
                        return@withContext when (response.body()?.meta?.statusCode) {
                            400 -> {

                                ResponseHandler.OnFailed(
                                    response.body()?.meta?.statusCode!!,
                                    response.body()?.meta?.message!!,
                                    "0"
                                )
                            }
                            401 -> {
                                ResponseHandler.OnFailed(
                                    HttpErrorCode.UNAUTHORIZED.code,
                                    response.body()?.meta?.message!!,
                                    response.body()?.meta?.statusCode!!.toString()
                                )
                            }
                            else -> ResponseHandler.OnSuccessResponse(response.body())
                        }
                    }
                    response.code() == 401 -> {
                        return@withContext parseUnAuthorizeResponse(response.errorBody())
                    }
                    response.code() == 422 -> {
                        return@withContext parseServerSideErrorResponse(response.errorBody())
                    }
                    response.code() == 500 -> {
                        return@withContext ResponseHandler.OnFailed(
                            HttpErrorCode.NOT_DEFINED.code,
                            "",
                            response.body()?.meta?.messageCode.toString()
                        )
                    }
                    else -> {
                        return@withContext parseUnKnownStatusCodeResponse(response.errorBody())
                    }
                }
            } catch (e: Exception) {
                DebugLog.print(e)
                return@withContext if (
                    e is UnknownHostException ||
                    e is ConnectionShutdownException
                ) {
                    ResponseHandler.OnFailed(HttpErrorCode.NO_CONNECTION.code, "", "")
                } else if (e is SocketTimeoutException || e is IOException ||
                    e is NetworkErrorException
                ) {
                    ResponseHandler.OnFailed(HttpErrorCode.NOT_DEFINED.code, "", "")
                } else {
                    ResponseHandler.OnFailed(HttpErrorCode.NOT_DEFINED.code, "", "")
                }
            }
        }
    }

    /**
     * Response parsing for 401 status code
     **/
    private fun parseUnAuthorizeResponse(response: ResponseBody?): ResponseHandler.OnFailed<Nothing> {
        val message: String
        val bodyString = response?.string() ?: Constants.EMPTY
        val responseWrapper: ErrorWrapper = JSON.readValue(bodyString)
        message = if (responseWrapper.meta?.statusCode == 401) {
            if (responseWrapper.errors != null) {
                HttpCommonMethod.getErrorMessage(responseWrapper.errors)
            } else {
                responseWrapper.meta.message.toString()
            }
        } else {
            responseWrapper.meta?.message.toString()
        }
        return ResponseHandler.OnFailed(
            data = null,
            code = HttpErrorCode.UNAUTHORIZED.code,
            message = message,
            messageCode = responseWrapper.meta?.messageCode.toString()
        )
    }

    /**
     * Response parsing for 422 status code
     * */
    private fun parseServerSideErrorResponse(response: ResponseBody?): ResponseHandler.OnFailed<Nothing> {
        val message: String
        val bodyString = response?.string()
        val responseWrapper: ErrorWrapper = JSON.readValue(bodyString!!)

        message = if (responseWrapper.meta!!.statusCode == 422) {
            if (responseWrapper.errors != null) {
                HttpCommonMethod.getErrorMessage(responseWrapper.errors)
            } else {
                responseWrapper.meta.message.toString()
            }
        } else {
            responseWrapper.meta.message.toString()
        }
        return ResponseHandler.OnFailed(
            HttpErrorCode.EMPTY_RESPONSE.code,
            message,
            responseWrapper.meta.messageCode.toString()
        )
    }

    /**
     * Response parsing for unknown status code
     * */
    private fun parseUnKnownStatusCodeResponse(response: ResponseBody?): ResponseHandler.OnFailed<Nothing> {
        val bodyString = response?.string()
        val responseWrapper: ErrorWrapper = JSON.readValue(bodyString!!)
        val message = if (responseWrapper.meta!!.statusCode == 422) {
            if (responseWrapper.errors != null) {
                HttpCommonMethod.getErrorMessage(responseWrapper.errors)
            } else {
                responseWrapper.meta.message.toString()
            }
        } else {
            responseWrapper.meta.message.toString()
        }
        return if (message.isEmpty()) {
            ResponseHandler.OnFailed(
                HttpErrorCode.EMPTY_RESPONSE.code,
                message,
                responseWrapper.meta.messageCode.toString()
            )
        } else {
            ResponseHandler.OnFailed(
                HttpErrorCode.NOT_DEFINED.code,
                message,
                responseWrapper.meta.messageCode.toString()
            )
        }
    }

    /**
     * This is the Base suspended method which is used for making the call of an Api using graphql
     * Created Sajid shaikh
    20/march/2024
    graphql base
     */

    suspend fun <T : Operation.Data> graphQlApiCall(call: suspend () -> ApolloResponse<T>): ResponseHandler<ApolloResponse<T>> {
        try {
            val response = call.invoke()
            when {
                response == null -> {
                    return ResponseHandler.OnFailed(
                        code = HttpErrorCode.BAD_RESPONSE.code,
                        message = HttpErrorCode.BAD_RESPONSE.message,
                        messageCode = null,
                    )
                }

                response.hasErrors() -> {

                    val errorModel = HttpCommonMethod.getErrorMessageForGraph(
                        response.errors
                    )

                    //                val error = response.errors?.let { GraphQLErrors(it) }
                    //                Log.i("madmad", "onLoginApi: here2")
                    return ResponseHandler.OnFailed(
                        code = errorModel.first,
                        message = errorModel.second,
                        messageCode = errorModel.third,
                    )
                }

                else -> {
                    return ResponseHandler.OnSuccessResponse(response)
                }
            }

        } catch (e: java.lang.Exception) {
            when (e) {
                is ApolloNetworkException -> {
                    return ResponseHandler.OnFailed(
                        code = HttpErrorCode.NO_CONNECTION.code,
                        message = HttpErrorCode.NO_CONNECTION.message,
                        messageCode = null,
                    )
                }

                is ApolloHttpException -> {
                    return ResponseHandler.OnFailed(
                        code = HttpErrorCode.BAD_RESPONSE.code,
                        message = e.message,
                        messageCode = null,
                    )
                }

                else -> {
                    return ResponseHandler.OnFailed(
                        code = HttpErrorCode.BAD_RESPONSE.code,
                        message = e.message,
                        messageCode = null,
                    )
                }

            }
        }
    }


}
