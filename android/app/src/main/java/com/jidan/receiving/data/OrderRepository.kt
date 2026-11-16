package com.jidan.receiving.data

/** 单据计价与提交（开发规格 5.2）。 */
class OrderRepository(
    private val client: ApiClient,
) {
    suspend fun quote(req: QuoteRequest): QuoteResult =
        client.runApi { this.quote(req) }

    suspend fun submit(req: SubmitRequest): SubmittedDto =
        client.runApi { this.submit(req) }
}
