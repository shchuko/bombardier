package com.itmo.microservices.demo.bombardier.external

import com.itmo.microservices.demo.bombardier.external.communicator.ExternalServiceToken
import com.itmo.microservices.demo.bombardier.external.communicator.InvalidExternalServiceResponseException
import com.itmo.microservices.demo.bombardier.external.communicator.UserAwareExternalServiceApiCommunicator
import com.itmo.microservices.demo.bombardier.external.knownServices.ServiceDescriptor
import com.itmo.microservices.demo.bombardier.external.storage.UserStorage
import org.springframework.http.HttpStatus
import java.net.URL
import java.time.Duration
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
class UserNotAuthenticatedException(username: String) : Exception(username)

class RealExternalService(override val descriptor: ServiceDescriptor, private val userStorage: UserStorage) : ExternalServiceApi {
    private val executorService = Executors.newFixedThreadPool(4)
    private val communicator = UserAwareExternalServiceApiCommunicator(descriptor.url, executorService)

    suspend fun getUserSession(id: UUID): ExternalServiceToken {
        val username = getUser(id).name

        return communicator.getUserSession(username) ?: throw UserNotAuthenticatedException(username)
    }

    override suspend fun getUser(id: UUID): User {
        return userStorage.get(id)
    }

    override suspend fun createUser(name: String): User {
        val user = communicator.executeWithDeserialize<User>("/users") {
            jsonPost(
                "name" to name,
                "password" to "pwd_$name"
            )
        }

        communicator.authenticate(name, "pwd_$name")

        userStorage.create(user)

        return user
    }

    override suspend fun userFinancialHistory(userId: UUID, orderId: UUID?): List<UserAccountFinancialLogRecord> {
        val session = getUserSession(userId)
        val url = if (orderId != null) "/finlog?orderId=$orderId" else "/finlog"

        return communicator.executeWithAuthAndDeserialize(url, session)
    }

    override suspend fun createOrder(userId: UUID): Order {
        val session = getUserSession(userId)

        return communicator.executeWithAuthAndDeserialize("/orders", session) {
            post()
        }
    }

    override suspend fun getOrder(userId: UUID, orderId: UUID): Order {
        val session = getUserSession(userId)

        return communicator.executeWithAuthAndDeserialize("/orders/$orderId", session)
    }

    override suspend fun getItems(userId: UUID, available: Boolean): List<CatalogItem> {
        val session = getUserSession(userId)

        return communicator.executeWithAuthAndDeserialize("/items?available=$available", session)
    }

    override suspend fun putItemToOrder(userId: UUID, orderId: UUID, itemId: UUID, amount: Int): Boolean {
        val session = getUserSession(userId)

        val okCode = HttpStatus.OK.value()
        val badCode = HttpStatus.BAD_REQUEST.value()

        val code = try {
            communicator.executeWithAuth("/orders/$orderId/items/$itemId?amount=$amount", session)
        }
        catch (e: InvalidExternalServiceResponseException) {
            if (e.code != badCode) {
                throw e
            }
            badCode
        }

        return code == okCode
    }

    override suspend fun bookOrder(userId: UUID, orderId: UUID): BookingDto {
        val session = getUserSession(userId)

        return communicator.executeWithAuthAndDeserialize("/orders/$orderId/bookings", session) {
            post()
        }
    }

    override suspend fun getDeliverySlots(userId: UUID, number: Int): List<Duration> {
        val session = getUserSession(userId)

        return communicator.executeWithAuthAndDeserialize("/delivery/slots?number=$number", session)
    }

    override suspend fun setDeliveryTime(userId: UUID, orderId: UUID, slot: Duration): BookingDto {
        val session = getUserSession(userId)

        return communicator.executeWithAuthAndDeserialize("/orders/$orderId/delivery?slot=${slot.seconds}", session) {
            post()
        }
    }

    override suspend fun payOrder(userId: UUID, orderId: UUID): PaymentSubmissionDto {
        val session = getUserSession(userId)

        return communicator.executeWithAuthAndDeserialize("/orders/$orderId/payment", session) {
            post()
        }
    }

    override suspend fun simulateDelivery(userId: UUID, orderId: UUID) {
        TODO("Remove this method from interface and its usages")
    }

    override suspend fun abandonedCardHistory(orderId: UUID): List<AbandonedCardLogRecord> {
        TODO("Not yet implemented")
    }

    override suspend fun getBookingHistory(bookingId: UUID): List<BookingLogRecord> {
        TODO("Not yet implemented")
    }

    override suspend fun deliveryLog(orderId: UUID): DeliveryInfoRecord {
        TODO("Not yet implemented")
    }
}