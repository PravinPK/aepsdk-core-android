/*
  Copyright 2022 Adobe. All rights reserved.
  This file is licensed to you under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License. You may obtain a copy
  of the License at http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software distributed under
  the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
  OF ANY KIND, either express or implied. See the License for the specific language
  governing permissions and limitations under the License.
 */

package com.adobe.marketing.mobile.internal.eventhub

import androidx.annotation.NonNull
import com.adobe.marketing.mobile.AdobeCallback
import com.adobe.marketing.mobile.AdobeCallbackWithError
import com.adobe.marketing.mobile.AdobeError
import com.adobe.marketing.mobile.Event
import com.adobe.marketing.mobile.Extension
import com.adobe.marketing.mobile.ExtensionError
import com.adobe.marketing.mobile.ExtensionErrorCallback
import com.adobe.marketing.mobile.LoggingMode
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.util.SerialWorkDispatcher
import java.lang.Exception
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * EventHub class is responsible for delivering events to listeners and maintaining registered extension's lifecycle.
 */
internal class EventHub {

    companion object {
        const val LOG_TAG = "EventHub"
        var shared = EventHub()
    }

    /**
     * Executor to initialize and shutdown extensions
     */
    private val extensionInitExecutor: ExecutorService by lazy { Executors.newCachedThreadPool() }

    /**
     * Executor for scheduled response listeners
     */
    private val scheduledExecutor: ScheduledExecutorService by lazy { Executors.newSingleThreadScheduledExecutor() }

    /**
     * Executor to serialize EventHub operations
     */
    private val eventHubExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }

    private val registeredExtensions: ConcurrentHashMap<String, ExtensionContainer> = ConcurrentHashMap()
    private val responseEventListeners: ConcurrentLinkedQueue<ResponseListenerContainer> = ConcurrentLinkedQueue()
    private val lastEventNumber: AtomicInteger = AtomicInteger(0)
    private var hubStarted = false

    /**
     * Implementation of [SerialWorkDispatcher.WorkHandler] that is responsible for dispatching
     * an [Event] "e". Dispatch is regarded complete when [SerialWorkDispatcher.WorkHandler.doWork] finishes for "e".
     */
    private val dispatchJob: SerialWorkDispatcher.WorkHandler<Event> = SerialWorkDispatcher.WorkHandler { event ->
        // TODO: Perform pre-processing

        // Handle response event listeners
        if (event.responseID != null) {
            val matchingResponseListeners = responseEventListeners.filterRemove { listener ->
                if (listener.shouldNotify(event)) {
                    listener.timeoutTask?.cancel(false)
                    true
                } else {
                    false
                }
            }

            matchingResponseListeners.forEach { listener ->
                listener.notify(event)
            }
        }

        // Notify to extensions for processing
        registeredExtensions.values.forEach {
            it.eventProcessor.offer(event)
        }

        // TODO: Record events in event history database.
    }

    /**
     * Responsible for processing and dispatching each event.
     */
    private val eventDispatcher: SerialWorkDispatcher<Event> = SerialWorkDispatcher("EventHub", dispatchJob)

    /**
     * A cache that maps UUID of an Event to an internal sequence of its dispatch.
     */
    private val eventNumberMap: ConcurrentHashMap<String, Int> = ConcurrentHashMap<String, Int>()

    init {
        registerExtension(EventHubPlaceholderExtension::class.java) {}
    }

    /**
     * `EventHub` will begin processing `Event`s when this API is invoked.
     */
    fun start() {
        eventHubExecutor.submit {
            this.hubStarted = true
            this.eventDispatcher.start()
            this.shareEventHubSharedState()
            MobileCore.log(LoggingMode.DEBUG, LOG_TAG, "Event Hub successfully started")
        }
    }

    /**
     * Dispatches a new [Event] to all listeners who have registered for the event type and source.
     * If the `event` has a `mask`, this method will attempt to record the `event` in `eventHistory`.
     * See [eventDispatcher] for more details.
     *
     * @param event the [Event] to be dispatched to listeners
     */
    fun dispatch(@NonNull event: Event) {
        eventHubExecutor.submit {
            // Assign the next available event number to the event.
            eventNumberMap[event.uniqueIdentifier] = lastEventNumber.incrementAndGet()

            // Offer event to the serial dispatcher to perform operations on the event.
            if (eventDispatcher.offer(event)) {
                MobileCore.log(
                    LoggingMode.VERBOSE,
                    LOG_TAG,
                    "Dispatching Event #${eventNumberMap[event.uniqueIdentifier]} - ($event)"
                )
            } else {
                MobileCore.log(
                    LoggingMode.WARNING,
                    LOG_TAG,
                    "Failed to dispatch event #${eventNumberMap[event.uniqueIdentifier]} - ($event)"
                )
            }

            // TODO: Record event to event history database if required.
        }
    }

    /**
     * Registers a new `Extension` to the `EventHub`. This `Extension` must extends `Extension` class
     *
     * @param extensionClass The class of extension to register
     * @param completion Invoked when the extension has been registered or failed to register
     */
    fun registerExtension(extensionClass: Class<out Extension>?, completion: (error: EventHubError) -> Unit) {
        eventHubExecutor.submit {
            if (extensionClass == null) {
                completion(EventHubError.ExtensionInitializationFailure)
                return@submit
            }

            val extensionTypeName = extensionClass.extensionTypeName
            if (registeredExtensions.containsKey(extensionTypeName)) {
                completion(EventHubError.DuplicateExtensionName)
                return@submit
            }

            val container = ExtensionContainer(extensionClass, extensionInitExecutor, completion)
            registeredExtensions[extensionTypeName] = container
        }
    }

    /**
     * Unregisters the extension from the `EventHub` if registered
     * @param extensionClass The class of extension to unregister
     * @param completion Invoked when the extension has been unregistered or failed to unregister
     */
    fun unregisterExtension(extensionClass: Class<out Extension>?, completion: ((error: EventHubError) -> Unit)) {
        eventHubExecutor.submit {
            val extensionName = extensionClass?.extensionTypeName
            val container = registeredExtensions.remove(extensionName)

            if (container != null) {
                container.shutdown()
                shareEventHubSharedState()
                completion(EventHubError.None)
            } else {
                completion(EventHubError.ExtensionNotRegistered)
            }
        }
    }

    /**
     * Registers an event listener which will be invoked when the response event to trigger event is dispatched
     * @param triggerEvent An [Event] which will trigger a response event
     * @param timeoutMS A timeout in milliseconds, if the response listener is not invoked within the timeout, then the `EventHub` invokes the fail method.
     * @param listener An [AdobeCallbackWithError] which will be invoked whenever the [EventHub] receives the response [Event] for trigger event
     */
    fun registerResponseListener(triggerEvent: Event, timeoutMS: Long, callback: AdobeCallbackWithError<Event>) {
        eventHubExecutor.submit {
            val triggerEventId = triggerEvent.uniqueIdentifier
            val timeoutCallable: Callable<Unit> = Callable {
                responseEventListeners.filterRemove { it.triggerEventId == triggerEventId }
                try {
                    callback.fail(AdobeError.CALLBACK_TIMEOUT)
                } catch (ex: Exception) {
                    MobileCore.log(LoggingMode.DEBUG, LOG_TAG, "Exception thrown from ResponseListener. $ex")
                }
            }
            val timeoutTask =
                scheduledExecutor.schedule(timeoutCallable, timeoutMS, TimeUnit.MILLISECONDS)

            responseEventListeners.add(
                ResponseListenerContainer(
                    triggerEventId,
                    timeoutTask,
                    callback
                )
            )
        }
    }

    /**
     * Registers an [EventListener] which will be invoked whenever a event with matched type and source is dispatched
     * @param type A String indicating the event type the current listener is listening for
     * @param source A `String` indicating the event source the current listener is listening for
     * @param listener An [AdobeCallback] which will be invoked whenever the [EventHub] receives a event with matched type and source
     */
    fun registerListener(eventType: String, eventSource: String, listener: AdobeCallback<Event>) {
        eventHubExecutor.submit {
            val eventHubContainer = getExtensionContainer(EventHubPlaceholderExtension::class.java)
            eventHubContainer?.registerEventListener(eventType, eventSource, { listener.call(it) })
        }
    }

    /**
     * Sets the shared state for the extension - [extensionName] with [data]
     * TODO : Make the [data] parameter immutable when EventData#toImmutableMap() is implemented.
     *
     * @param sharedStateType the type of shared state that needs to be set.
     * @param extensionName the name of the extension for which the state is being set
     * @param data a map representing state of [extensionName] extension. Passing null will set the extension's
     *              shared state on pending until it is resolved. Another call with non-null state is expected in order
     *              to resolve this share state.
     * @param event The [Event] for which the state is being set. Passing null will set the state for the next shared
     *              state version.
     * @param errorCallback the callback which will be notified in the event of an error
     *
     * @return true if the state was successfully set, false otherwise
     */
    fun setSharedState(
        sharedStateType: SharedStateType,
        extensionName: String?,
        data: MutableMap<String, Any?>?,
        event: Event?,
        errorCallback: ExtensionErrorCallback<ExtensionError>?
    ): Boolean {

        val setSharedStateCallable: Callable<Boolean> = Callable<Boolean> {

            if (extensionName.isNullOrBlank()) {
                MobileCore.log(LoggingMode.ERROR, LOG_TAG, "Unable to set SharedState for extension: [$extensionName]. ExtensionName is invalid.")

                errorCallback?.error(ExtensionError.BAD_NAME)
                return@Callable false
            }

            val extensionContainer: ExtensionContainer? = getExtensionContainer(extensionName)

            if (extensionContainer == null) {
                MobileCore.log(LoggingMode.ERROR, LOG_TAG, "Error setting SharedState for extension: [$extensionName]. Extension may not have been registered.")

                errorCallback?.error(ExtensionError.UNEXPECTED_ERROR)
                return@Callable false
            }

            // Find the version where this state needs to be set
            val version: Int = if (event == null) {
                // Use the next available version if event is null
                lastEventNumber.incrementAndGet()
            } else {
                // Fetch the event number for the event if it has been dispatched.
                // If no such event exists, use the next available sequence number
                getEventNumber(event) ?: lastEventNumber.incrementAndGet()
            }

            val result: SharedState.Status = extensionContainer.setSharedState(sharedStateType, data, version)
            val wasSet = (result == SharedState.Status.SET)

            // Check if the new state can be dispatched as a state change event(currently implies a
            // non null/non pending state according to the ExtensionAPI)
            val shouldDispatch = (data == null)

            if (shouldDispatch && wasSet) {
                // If the new state can be dispatched and was successfully
                // set (via a new state being created or a state being updated),
                // dispatch a shared state notification.
                //  TODO: dispatch()
            }
            return@Callable (wasSet || result == SharedState.Status.PENDING)
        }

        return eventHubExecutor.submit(setSharedStateCallable).get()
    }

    /**
     * Retrieves the shared state for the extension [extensionName] at the [event]
     *
     * @param sharedStateType the type of shared state that needs to be retrieved.
     * @param extensionName the name of the extension for which the state is being retrieved
     * @param event The [Event] for which the state is being retrieved. Passing null will retrieve latest state available.
     *              state version.
     * @param errorCallback the callback which will be notified in the event of an error
     * @return a [Map] containing the shared state data at [event],
     *         null if the state is pending, not yet set or, in case of an error
     */
    fun getSharedState(
        sharedStateType: SharedStateType,
        extensionName: String?,
        event: Event?,
        errorCallback: ExtensionErrorCallback<ExtensionError>?
    ): Map<String, Any?>? {

        val getSharedStateCallable: Callable<Map<String, Any?>?> = Callable {
            if (extensionName.isNullOrEmpty() || extensionName.isBlank()) {
                MobileCore.log(LoggingMode.ERROR, LOG_TAG, "Unable to get SharedState. State name [$extensionName] is invalid.")

                errorCallback?.error(ExtensionError.BAD_NAME)
                return@Callable null
            }

            val extensionContainer: ExtensionContainer? = getExtensionContainer(extensionName)

            if (extensionContainer == null) {
                MobileCore.log(
                    LoggingMode.ERROR, LOG_TAG,
                    "Error retrieving SharedState for extension: [$extensionName]." +
                        "Extension may not have been registered."
                )
                errorCallback?.error(ExtensionError.UNEXPECTED_ERROR)
                return@Callable null
            }

            val version: Int = if (event == null) {
                // Get the most recent number if event is not specified
                SharedStateManager.VERSION_LATEST
            } else {
                // Fetch event number from the provided event.
                // If not such event was dispatched, return the most recent state.
                getEventNumber(event) ?: SharedStateManager.VERSION_LATEST
            }

            return@Callable extensionContainer.getSharedState(sharedStateType, version)?.data
        }

        return eventHubExecutor.submit(getSharedStateCallable).get()
    }

    /**
     * Clears all shared state previously set by [extensionName].
     *
     * @param sharedStateType the type of shared state that needs to be cleared.
     * @param extensionName the name of the extension for which the state is being cleared
     * @param errorCallback the callback which will be notified in the event of an error
     * @return true - if the shared state has been cleared, false otherwise
     */
    fun clearSharedState(
        sharedStateType: SharedStateType,
        extensionName: String?,
        errorCallback: ExtensionErrorCallback<ExtensionError>?
    ): Boolean {
        val clearSharedStateCallable: Callable<Boolean> = Callable {
            if (extensionName.isNullOrEmpty() || extensionName.isBlank()) {
                MobileCore.log(LoggingMode.ERROR, LOG_TAG, "Unable to clear SharedState. State name [$extensionName] is invalid.")

                errorCallback?.error(ExtensionError.BAD_NAME)
                return@Callable false
            }

            val extensionContainer: ExtensionContainer? = getExtensionContainer(extensionName)

            if (extensionContainer == null) {
                MobileCore.log(
                    LoggingMode.ERROR,
                    LOG_TAG, "Error clearing SharedState for extension: [$extensionName]. Extension may not have been registered."
                )
                errorCallback?.error(ExtensionError.UNEXPECTED_ERROR)
                return@Callable false
            }

            return@Callable extensionContainer.clearSharedState(sharedStateType)
        }

        return eventHubExecutor.submit(clearSharedStateCallable).get()
    }

    /**
     * Stops processing events and shuts down all registered extensions.
     */
    fun shutdown() {
        // Shutdown and clear all the extensions.
        eventHubExecutor.submit {
            eventDispatcher.shutdown()

            // Unregister all extensions
            registeredExtensions.forEach { (_, extensionContainer) ->
                extensionContainer.shutdown()
            }
            registeredExtensions.clear()
        }
        eventHubExecutor.shutdown()
    }

    private fun shareEventHubSharedState() {
        if (!hubStarted) return
        // Update shared state with registered extensions
    }

    /**
     * Retrieve the event number for the Event from the [eventNumberMap]
     *
     * @param [event] the Event for which the event number should be resolved
     * @return the event number for the event if it exists (if it has been recorded/dispatched),
     *         null otherwise
     */
    private fun getEventNumber(event: Event?): Int? {
        val eventUUID = event?.uniqueIdentifier
        return if (eventUUID == null) {
            null
        } else eventNumberMap[eventUUID]
    }

    /**
     * Retrieves a registered [ExtensionContainer] with [extensionClass] provided.
     *
     * @param [extensionClass] the extension class for which an [ExtensionContainer] should be fetched.
     * @return [ExtensionContainer] with [extensionName] provided if one was registered,
     *         null if no extension is registered with the [extensionName]
     */
    internal fun getExtensionContainer(extensionClass: Class<out Extension>): ExtensionContainer? {
        val extensionTypeName = extensionClass.extensionTypeName
        return if (extensionTypeName != null) {
            registeredExtensions[extensionTypeName]
        } else {
            null
        }
    }

    /**
     * Retrieves a registered [ExtensionContainer] with [extensionTypeName] provided.
     *
     * @param [extensionName] the name of the extension for which an [ExtensionContainer] should be fetched.
     *        This should match [Extension.name] of an extension registered with the event hub.
     * @return [ExtensionContainer] with [extensionName] provided if one was registered,
     *         null if no extension is registered with the [extensionName]
     */
    private fun getExtensionContainer(extensionName: String): ExtensionContainer? {
        val extensionTypeName = getExtensionTypeName(extensionName)
        return if (extensionTypeName == null) {
            null
        } else {
            registeredExtensions[extensionTypeName]
        }
    }

    /**
     * Retrieves the [extensionTypeName] for the provided [extensionName]
     * This is required because [registeredExtensions] maintains a mapping between [extensionTypeName]
     * and [ExtensionContainer] and most state based operations rely on the name of an extension.
     *
     * @param [extensionName] the name of the extension for which an extensionTypeName should be fetched.
     *        This should match [Extension.name] of an extension registered with the event hub.
     * @return the [extensionTypeName] for the provided [extensionName]
     */
    private fun getExtensionTypeName(extensionName: String): String? {
        return registeredExtensions.entries.firstOrNull { extensionName == it.value.sharedStateName }?.key
    }
}

private fun <T> MutableCollection<T>.filterRemove(predicate: (T) -> Boolean): MutableCollection<T> {
    val ret = mutableListOf<T>()
    this.removeAll {
        if (predicate(it)) {
            ret.add(it)
            true
        } else {
            false
        }
    }
    return ret
}
