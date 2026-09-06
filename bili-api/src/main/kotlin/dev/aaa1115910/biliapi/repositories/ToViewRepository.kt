package dev.aaa1115910.biliapi.repositories

import bilibili.app.interfaces.v1.HistoryGrpcKt
import dev.aaa1115910.biliapi.entity.ApiType
import dev.aaa1115910.biliapi.entity.user.ToViewData
import dev.aaa1115910.biliapi.http.BiliHttpApi
import dev.aaa1115910.biliapi.util.AvBvConverter
import org.koin.core.annotation.Single

@Single
class ToViewRepository(
    private val authRepository: AuthRepository,
    private val channelRepository: ChannelRepository
) {
    private val historyStub
        get() = runCatching {
            HistoryGrpcKt.HistoryCoroutineStub(channelRepository.defaultChannel!!)
        }.getOrNull()

    suspend fun getToView(
        cursor: Long,
        preferApiType: ApiType = ApiType.Web
    ): ToViewData {
        return when (preferApiType) {
            ApiType.Web -> {
                val data = BiliHttpApi.getToView(
                    // viewAt = cursor,
                    sessData = authRepository.sessionData!!,
                ).getResponseData()
                print(data)
                ToViewData.fromToViewResponse(data)
            }

            ApiType.App -> {
                val data = BiliHttpApi.getToView(
                    // viewAt = cursor,
                    sessData = authRepository.sessionData!!,
                ).getResponseData()
                ToViewData.fromToViewResponse(data)
            }
        }
    }

    suspend fun deleteToView(
        avid: Long,
        preferApiType: ApiType = ApiType.Web
    ): Boolean {
        return runCatching {
            BiliHttpApi.deleteToView(
                avid = avid,
                csrf = authRepository.biliJct!!,
                sessData = authRepository.sessionData!!
            ).code == 0
        }.getOrDefault(false)
    }

    suspend fun clearToView(
        preferApiType: ApiType = ApiType.Web
    ): Boolean {
        return runCatching {
            when (preferApiType) {
                ApiType.Web, ApiType.App -> {
                    BiliHttpApi.clearToView(
                        csrf = authRepository.biliJct!!,
                        sessData = authRepository.sessionData!!
                    ).code == 0
                }
            }
        }.getOrDefault(false)
    }

    suspend fun addToView(
        avid: Long,
        preferApiType: ApiType = ApiType.Web
    ): Boolean {
        return runCatching {
            when (preferApiType) {
                ApiType.Web, ApiType.App -> {
                    BiliHttpApi.addToView(
                        bvid = AvBvConverter.av2bv(avid),
                        csrf = authRepository.biliJct!!,
                        sessData = authRepository.sessionData!!
                    ).code == 0
                }
            }
        }.getOrDefault(false)
    }
}
