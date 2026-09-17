package com.xiaoyuan.renovation.data.remote

import com.xiaoyuan.renovation.data.model.AuthStateDto
import com.xiaoyuan.renovation.data.model.BatchDeleteInDto
import com.xiaoyuan.renovation.data.model.BatchDeleteResultDto
import com.xiaoyuan.renovation.data.model.CategoryDto
import com.xiaoyuan.renovation.data.model.CellSaveResultDto
import com.xiaoyuan.renovation.data.model.CredentialsInDto
import com.xiaoyuan.renovation.data.model.ExpenseDto
import com.xiaoyuan.renovation.data.model.ExpenseInDto
import com.xiaoyuan.renovation.data.model.HealthDto
import com.xiaoyuan.renovation.data.model.ImportReportDto
import com.xiaoyuan.renovation.data.model.ItemDto
import com.xiaoyuan.renovation.data.model.ItemInDto
import com.xiaoyuan.renovation.data.model.ItemListDto
import com.xiaoyuan.renovation.data.model.ListInDto
import com.xiaoyuan.renovation.data.model.LoginResultDto
import com.xiaoyuan.renovation.data.model.MatrixCellInDto
import com.xiaoyuan.renovation.data.model.MatrixDto
import com.xiaoyuan.renovation.data.model.NameInDto
import com.xiaoyuan.renovation.data.model.OkDto
import com.xiaoyuan.renovation.data.model.RecordInDto
import com.xiaoyuan.renovation.data.model.RecordPatchDto
import com.xiaoyuan.renovation.data.model.RoomDto
import com.xiaoyuan.renovation.data.model.PurgeResultDto
import com.xiaoyuan.renovation.data.model.SummaryDto
import com.xiaoyuan.renovation.data.model.TrashItemDto
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/** 与后端 FastAPI 的端点一一对应。 */
interface ApiService {

    /* ---------- 登录 ---------- */

    /** 免登录：查询是否已创建账号、当前 token 是否有效。 */
    @GET("api/auth/state")
    suspend fun authState(): AuthStateDto

    /** 免登录：首次创建管理员账号，建成即视为登录。 */
    @POST("api/auth/setup")
    suspend fun setup(@Body body: CredentialsInDto): LoginResultDto

    /** 免登录：登录换取 token。 */
    @POST("api/auth/login")
    suspend fun login(@Body body: CredentialsInDto): LoginResultDto

    /** 退出：服务端清 Cookie，安卓端自行丢弃本地 token。 */
    @POST("api/auth/logout")
    suspend fun logout(): OkDto

    /** 免登录的连通性探针，用于「测试连接」。 */
    @GET("api/health")
    suspend fun health(): HealthDto

    /* ---------- 清单 ---------- */

    /** 全部清单。请求头里那个「当前清单」由拦截器统一加，这个接口不看它。 */
    @GET("api/lists")
    suspend fun lists(): List<ItemListDto>

    @POST("api/lists")
    suspend fun createList(@Body body: ListInDto): ItemListDto

    @PUT("api/lists/{id}")
    suspend fun updateList(@Path("id") id: Int, @Body body: ListInDto): ItemListDto

    @DELETE("api/lists/{id}")
    suspend fun deleteList(@Path("id") id: Int): OkDto

    /* ---------- 总览 ---------- */

    @GET("api/summary")
    suspend fun summary(): SummaryDto

    /* ---------- 物料 ---------- */

    @GET("api/items")
    suspend fun items(
        @Query("category_id") categoryId: Int? = null,
        @Query("q") query: String? = null,
        @Query("status") status: String? = null,
    ): List<ItemDto>

    @GET("api/items/{id}")
    suspend fun item(@Path("id") id: Int): ItemDto

    @POST("api/items")
    suspend fun createItem(@Body body: ItemInDto): ItemDto

    @PUT("api/items/{id}")
    suspend fun updateItem(@Path("id") id: Int, @Body body: ItemInDto): ItemDto

    @DELETE("api/items/{id}")
    suspend fun deleteItem(@Path("id") id: Int): OkDto

    @POST("api/items/batch/delete")
    suspend fun batchDeleteItems(@Body body: BatchDeleteInDto): BatchDeleteResultDto

    /* ---------- 采购记录 ---------- */

    @POST("api/items/{id}/records")
    suspend fun addRecord(@Path("id") itemId: Int, @Body body: RecordInDto): ItemDto

    @DELETE("api/items/{id}/records")
    suspend fun clearRecords(@Path("id") itemId: Int): ItemDto

    @PUT("api/records/{id}")
    suspend fun updateRecord(@Path("id") recordId: Int, @Body body: RecordPatchDto): ItemDto

    @DELETE("api/records/{id}")
    suspend fun deleteRecord(@Path("id") recordId: Int): ItemDto

    /* ---------- 分组 ---------- */

    @GET("api/rooms")
    suspend fun rooms(): List<RoomDto>

    @POST("api/rooms")
    suspend fun createRoom(@Body body: NameInDto): RoomDto

    @PUT("api/rooms/{id}")
    suspend fun updateRoom(@Path("id") id: Int, @Body body: NameInDto): RoomDto

    @DELETE("api/rooms/{id}")
    suspend fun deleteRoom(@Path("id") id: Int): OkDto

    /* ---------- 分类 ---------- */

    @GET("api/categories")
    suspend fun categories(): List<CategoryDto>

    @POST("api/categories")
    suspend fun createCategory(@Body body: NameInDto): CategoryDto

    @PUT("api/categories/{id}")
    suspend fun updateCategory(@Path("id") id: Int, @Body body: NameInDto): CategoryDto

    @DELETE("api/categories/{id}")
    suspend fun deleteCategory(@Path("id") id: Int): OkDto

    /* ---------- 分配矩阵 ---------- */

    @GET("api/matrix")
    suspend fun matrix(): MatrixDto

    @PUT("api/matrix/cell")
    suspend fun saveCell(@Body body: MatrixCellInDto): CellSaveResultDto

    /* ---------- 额外费用（运费/安装费）---------- */

    @GET("api/expenses")
    suspend fun expenses(): List<ExpenseDto>

    @POST("api/expenses")
    suspend fun createExpense(@Body body: ExpenseInDto): ExpenseDto

    @PUT("api/expenses/{id}")
    suspend fun updateExpense(@Path("id") id: Int, @Body body: ExpenseInDto): ExpenseDto

    @DELETE("api/expenses/{id}")
    suspend fun deleteExpense(@Path("id") id: Int): OkDto

    /* ---------- 回收站 ---------- */

    @GET("api/trash")
    suspend fun trash(): List<TrashItemDto>

    @POST("api/trash/{id}/restore")
    suspend fun restoreItem(@Path("id") id: Int): TrashItemDto

    @DELETE("api/trash/{id}")
    suspend fun purgeItem(@Path("id") id: Int): OkDto

    @DELETE("api/trash")
    suspend fun purgeTrash(): PurgeResultDto

    /* ---------- 导入导出 ---------- */

    @Streaming
    @GET("api/export")
    suspend fun export(): Response<ResponseBody>

    @Streaming
    @GET("api/import/template")
    suspend fun importTemplate(): Response<ResponseBody>

    @Multipart
    @POST("api/import")
    suspend fun import(
        @Part file: MultipartBody.Part,
        @Part("mode") mode: RequestBody,
    ): ImportReportDto
}
