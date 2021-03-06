/*
 * Copyright (c) 2018-2020 DJI
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package dji.ux.beta.core.widget.vision

import dji.common.flightcontroller.*
import dji.common.flightcontroller.flightassistant.ObstacleAvoidanceSensorState
import dji.common.mission.activetrack.ActiveTrackMode
import dji.common.mission.tapfly.TapFlyMode
import dji.common.product.Model
import dji.keysdk.DJIKey
import dji.keysdk.FlightControllerKey
import dji.keysdk.ProductKey
import dji.thirdparty.io.reactivex.Completable
import dji.thirdparty.io.reactivex.Flowable
import dji.ux.beta.core.base.DJISDKModel
import dji.ux.beta.core.base.SchedulerProviderInterface
import dji.ux.beta.core.base.WidgetModel
import dji.ux.beta.core.base.uxsdkkeys.MessagingKeys
import dji.ux.beta.core.base.uxsdkkeys.ObservableInMemoryKeyedStore
import dji.ux.beta.core.base.uxsdkkeys.UXKey
import dji.ux.beta.core.base.uxsdkkeys.UXKeys
import dji.ux.beta.core.model.WarningMessage
import dji.ux.beta.core.model.WarningMessageError
import dji.ux.beta.core.util.DataProcessor
import dji.ux.beta.core.util.ProductUtil
import java.util.*

/**
 * Widget Model for the [VisionWidget] used to define
 * the underlying logic and communication
 */
open class VisionWidgetModel(djiSdkModel: DJISDKModel,
                             private val keyedStore: ObservableInMemoryKeyedStore,
                             private val schedulerProvider: SchedulerProviderInterface
) : WidgetModel(djiSdkModel, keyedStore) {

    //region Fields
    private val statusMap: MutableMap<VisionSensorPosition, VisionSystemStatus> = HashMap()
    private val visionDetectionStateProcessor: DataProcessor<VisionDetectionState> = DataProcessor.create(
            VisionDetectionState.createInstance(false, 0.0, VisionSystemWarning.INVALID, null,
                    VisionSensorPosition.UNKNOWN, false, 0))
    private val isUserAvoidEnabledProcessor: DataProcessor<Boolean> = DataProcessor.create(true)
    private val flightModeProcessor: DataProcessor<FlightMode> = DataProcessor.create(FlightMode.GPS_ATTI)
    private val trackingModeProcessor: DataProcessor<ActiveTrackMode> = DataProcessor.create(ActiveTrackMode.TRACE)
    private val drawStatusProcessor: DataProcessor<VisionDrawStatus> = DataProcessor.create(VisionDrawStatus.OTHER)
    private val drawHeadingModeProcessor: DataProcessor<VisionDrawHeadingMode> = DataProcessor.create(VisionDrawHeadingMode.FORWARD)
    private val tapFlyModeProcessor: DataProcessor<TapFlyMode> = DataProcessor.create(TapFlyMode.UNKNOWN)
    private val isFrontRadarOpenProcessor: DataProcessor<Boolean> = DataProcessor.create(false)
    private val isBackRadarOpenProcessor: DataProcessor<Boolean> = DataProcessor.create(false)
    private val isLeftRadarOpenProcessor: DataProcessor<Boolean> = DataProcessor.create(false)
    private val isRightRadarOpenProcessor: DataProcessor<Boolean> = DataProcessor.create(false)
    private val productModelProcessor: DataProcessor<Model> = DataProcessor.create(Model.UNKNOWN_AIRCRAFT)
    private val omniHorizontalAvoidanceEnabledProcessor: DataProcessor<Boolean> = DataProcessor.create(false)
    private val omniVerticalAvoidanceEnabledProcessor: DataProcessor<Boolean> = DataProcessor.create(false)
    private val landingProtectionEnabledProcessor: DataProcessor<Boolean> = DataProcessor.create(false)
    private val omniAvoidanceStateProcessor: DataProcessor<ObstacleAvoidanceSensorState> = DataProcessor.create(ObstacleAvoidanceSensorState.Builder().build())
    private val visionSystemStatusProcessor: DataProcessor<VisionSystemStatus> = DataProcessor.create(VisionSystemStatus.NORMAL)
    private val sendWarningMessageKey: UXKey = UXKeys.create(MessagingKeys.SEND_WARNING_MESSAGE)
    //endregion

    //region Data
    /**
     * Get the status of the vision system.
     */
    val visionSystemStatus: Flowable<VisionSystemStatus>
        get() = visionSystemStatusProcessor.toFlowable()

    /**
     * Get whether user avoidance is enabled.
     */
    val isUserAvoidanceEnabled: Flowable<Boolean>
        get() = isUserAvoidEnabledProcessor.toFlowable()

    /**
     * Get whether the product has vision sensors.
     */
    val isVisionSupportedByProduct: Flowable<Boolean>
        get() = productModelProcessor.toFlowable()
                .concatMap { model: Model? -> Flowable.just(ProductUtil.isVisionSupportedProduct(model)) }
    //endregion

    //region Actions
    /**
     * Send a warning message with the given reason.
     *
     * @param reason The reason to display on the warning message.
     * @param isUserAvoidanceEnabled Whether user avoidance is currently enabled.
     * @return Completable representing the success/failure of the set action.
     */
    fun sendWarningMessage(reason: String?, isUserAvoidanceEnabled: Boolean): Completable {
        val subCode = WarningMessageError.VISION_AVOID.value()
        val action = if (isUserAvoidanceEnabled) WarningMessage.Action.REMOVE else WarningMessage.Action.INSERT
        val builder = WarningMessage.Builder(WarningMessage.WarningType.VISION)
                .code(-1)
                .subCode(subCode)
                .reason(reason)
                .type(WarningMessage.Type.AUTO_DISAPPEAR).action(action)
        val warningMessage = builder.build()
        return keyedStore.setValue(sendWarningMessageKey, warningMessage)
                .subscribeOn(schedulerProvider.io())
    }

    //endregion

    //region Lifecycle
    override fun inSetup() {
        val visionDetectionStateKey: DJIKey = FlightControllerKey.createFlightAssistantKey(FlightControllerKey.VISION_DETECTION_STATE)
        val isUserAvoidEnabledKey: DJIKey = FlightControllerKey.createFlightAssistantKey(FlightControllerKey.INTELLIGENT_FLIGHT_ASSISTANT_IS_USERAVOID_ENABLE)
        val flightModeKey: DJIKey = FlightControllerKey.create(FlightControllerKey.FLIGHT_MODE)
        val trackingModeKey: DJIKey = FlightControllerKey.createFlightAssistantKey(FlightControllerKey.ACTIVE_TRACK_MODE)
        val drawStatusKey: DJIKey = FlightControllerKey.createFlightAssistantKey(FlightControllerKey.DRAW_STATUS)
        val drawHeadingModeKey: DJIKey = FlightControllerKey.createFlightAssistantKey(FlightControllerKey.DRAW_HEADING_MODE)
        val tapFlyModeKey: DJIKey = FlightControllerKey.createFlightAssistantKey(FlightControllerKey.TAP_FLY_MODE)
        val isFrontRadarOpenKey: DJIKey = FlightControllerKey.createFlightAssistantKey(FlightControllerKey.IS_FRONT_RADAR_OPEN)
        val isBackRadarOpenKey: DJIKey = FlightControllerKey.createFlightAssistantKey(FlightControllerKey.IS_BACK_RADAR_OPEN)
        val isLeftRadarOpenKey: DJIKey = FlightControllerKey.createFlightAssistantKey(FlightControllerKey.IS_LEFT_RADAR_OPEN)
        val isRightRadarOpenKey: DJIKey = FlightControllerKey.createFlightAssistantKey(FlightControllerKey.IS_RIGHT_RADAR_OPEN)
        val productModelKey: DJIKey = ProductKey.create(ProductKey.MODEL_NAME)
        val omniHorizontalAvoidanceEnabledKey = FlightControllerKey.createFlightAssistantKey(FlightControllerKey.OMNI_HORIZONTAL_AVOIDANCE_ENABLED)
        val omniVerticalAvoidanceEnabledKey = FlightControllerKey.createFlightAssistantKey(FlightControllerKey.OMNI_VERTICAL_AVOIDANCE_ENABLED)
        val landingProtectionEnabled: DJIKey = FlightControllerKey.createFlightAssistantKey(FlightControllerKey.LANDING_PROTECTION_ENABLED)
        val omniAvoidanceStateKey = FlightControllerKey.createFlightAssistantKey(FlightControllerKey.OMNI_PERCEPTION_AVOIDANCE_STATE)
        bindDataProcessor(visionDetectionStateKey, visionDetectionStateProcessor)
        bindDataProcessor(isUserAvoidEnabledKey, isUserAvoidEnabledProcessor)
        bindDataProcessor(flightModeKey, flightModeProcessor)
        bindDataProcessor(trackingModeKey, trackingModeProcessor)
        bindDataProcessor(drawStatusKey, drawStatusProcessor)
        bindDataProcessor(drawHeadingModeKey, drawHeadingModeProcessor)
        bindDataProcessor(tapFlyModeKey, tapFlyModeProcessor)
        bindDataProcessor(isFrontRadarOpenKey, isFrontRadarOpenProcessor)
        bindDataProcessor(isBackRadarOpenKey, isBackRadarOpenProcessor)
        bindDataProcessor(isLeftRadarOpenKey, isLeftRadarOpenProcessor)
        bindDataProcessor(isRightRadarOpenKey, isRightRadarOpenProcessor)
        bindDataProcessor(productModelKey, productModelProcessor)
        bindDataProcessor(omniHorizontalAvoidanceEnabledKey, omniHorizontalAvoidanceEnabledProcessor)
        bindDataProcessor(omniVerticalAvoidanceEnabledKey, omniVerticalAvoidanceEnabledProcessor)
        bindDataProcessor(landingProtectionEnabled, landingProtectionEnabledProcessor)
        bindDataProcessor(omniAvoidanceStateKey, omniAvoidanceStateProcessor)
    }

    override fun inCleanup() {
        // Nothing to clean
    }

    override fun updateStates() {
        if (!productConnectionProcessor.value) {
            visionSystemStatusProcessor.onNext(VisionSystemStatus.NORMAL)
        } else {
            addSingleVisionStatus()
            if (Model.MATRICE_300_RTK == productModelProcessor.value) {
                visionSystemStatusProcessor.onNext(omniHorizontalVerticalAvoidanceState)
            } else if (!ProductUtil.isMavic2SeriesProduct(productModelProcessor.value)) {
                visionSystemStatusProcessor.onNext(overallVisionSystemStatus)
            } else {
                visionSystemStatusProcessor.onNext(omnidirectionalVisionSystemStatus)
            }
        }
    }
    //endregion

    //region Helpers
    private fun addSingleVisionStatus() {
        val state = visionDetectionStateProcessor.value
        statusMap[state.position] = getSingleVisionSystemStatus(state)
    }

    /**
     * Get the status of all of the vision sensors on the aircraft.
     *
     * @return The overall status of all vision sensors on the aircraft.
     */
    private val overallVisionSystemStatus: VisionSystemStatus
        get() {
            var status = VisionSystemStatus.CLOSED
            for ((_, item) in statusMap) {
                if (item == VisionSystemStatus.NORMAL) {
                    status = VisionSystemStatus.NORMAL
                } else if (item == VisionSystemStatus.CLOSED) {
                    status = VisionSystemStatus.CLOSED
                } else {
                    status = VisionSystemStatus.DISABLED
                    break
                }
            }
            return status
        }

    /**
     * Get the status of a single vision sensor on the aircraft based on its vision detection state.
     *
     * @param state The state of single vision sensor on the aircraft.
     * @return The status of the vision sensor.
     */
    private fun getSingleVisionSystemStatus(state: VisionDetectionState): VisionSystemStatus {
        return if (isUserAvoidEnabledProcessor.value) {
            if (isVisionSystemEnabled && !state.isDisabled) {
                VisionSystemStatus.NORMAL
            } else {
                VisionSystemStatus.DISABLED
            }
        } else {
            VisionSystemStatus.CLOSED
        }
    }

    private val omniHorizontalVerticalAvoidanceState: VisionSystemStatus
        get() {
            val horizontalState: VisionSystemStatus =
                    if ((landingProtectionEnabledProcessor.value||omniHorizontalAvoidanceEnabledProcessor.value) &&  omniAvoidanceStateProcessor.value.
                            areVisualObstacleAvoidanceSensorsInHorizontalDirectionEnabled()) {
                        if (omniAvoidanceStateProcessor.value.areVisualObstacleAvoidanceSensorsInHorizontalDirectionWorking()) {
                            VisionSystemStatus.NORMAL
                        } else {
                            VisionSystemStatus.DISABLED
                        }
                    } else {
                        VisionSystemStatus.CLOSED
                    }
            val verticalState: VisionSystemStatus =
                    if (omniVerticalAvoidanceEnabledProcessor.value && omniAvoidanceStateProcessor.value.
                            areVisualObstacleAvoidanceSensorsInVerticalDirectionEnabled()) {
                        if (omniAvoidanceStateProcessor.value.
                                areVisualObstacleAvoidanceSensorsInHorizontalDirectionWorking()) {
                            VisionSystemStatus.NORMAL
                        } else {
                            VisionSystemStatus.DISABLED
                        }
                    } else {
                        VisionSystemStatus.CLOSED
                    }
            return if (horizontalState == VisionSystemStatus.NORMAL) {
                if (verticalState == VisionSystemStatus.NORMAL) {
                    VisionSystemStatus.OMNI_ALL
                } else {
                    VisionSystemStatus.OMNI_HORIZONTAL
                }
            } else {
                if (verticalState == VisionSystemStatus.NORMAL) {
                    VisionSystemStatus.OMNI_VERTICAL
                } else {
                    VisionSystemStatus.OMNI_CLOSED
                }
            }
        }

    /**
     * Get the status of the omnidirectional vision system.
     *
     * @return The status of the omnidirectional vision system.
     */
    private val omnidirectionalVisionSystemStatus: VisionSystemStatus
        get() {
            if (ProductUtil.isMavic2Enterprise(productModelProcessor.value)) {
                if (isAllOmnidirectionalDataOpen) {
                    return VisionSystemStatus.OMNI_ALL
                } else if (overallVisionSystemStatus == VisionSystemStatus.DISABLED) {
                    VisionSystemStatus.OMNI_DISABLED
                } else if (isNoseTailVisionNormal || isNoseTailDataOpen) {
                    return VisionSystemStatus.OMNI_FRONT_BACK
                }
            } else {
                if (overallVisionSystemStatus == VisionSystemStatus.NORMAL && isAllOmnidirectionalDataOpen) {
                    return VisionSystemStatus.OMNI_ALL
                } else if (overallVisionSystemStatus == VisionSystemStatus.DISABLED) {
                    VisionSystemStatus.OMNI_DISABLED
                } else if (isNoseTailVisionNormal && isNoseTailDataOpen) {
                    return VisionSystemStatus.OMNI_FRONT_BACK
                }
            }
            return VisionSystemStatus.OMNI_CLOSED
        }

    private val isAllOmnidirectionalDataOpen: Boolean
        get() = (isFrontRadarOpenProcessor.value
                && isBackRadarOpenProcessor.value
                && isLeftRadarOpenProcessor.value
                && isRightRadarOpenProcessor.value)

    private val isNoseTailDataOpen: Boolean
        get() = isFrontRadarOpenProcessor.value && isBackRadarOpenProcessor.value

    private val isNoseTailVisionNormal: Boolean
        get() = statusMap[VisionSensorPosition.NOSE] == VisionSystemStatus.NORMAL
                && statusMap[VisionSensorPosition.TAIL] == VisionSystemStatus.NORMAL

    /**
     * Whether the vision system is enabled. It could be disabled due to the flight mode,
     * tap mode, tracking mode, draw status, or hardware failure.
     *
     * @return `true` if the vision system is enabled, `false` otherwise.
     */
    private val isVisionSystemEnabled: Boolean
        get() = !isAttiMode(flightModeProcessor.value)
                && FlightMode.GPS_SPORT != flightModeProcessor.value
                && FlightMode.AUTO_LANDING != flightModeProcessor.value
                && isSupportedActiveTrackMode(trackingModeProcessor.value)
                && TapFlyMode.FREE != tapFlyModeProcessor.value
                && isDrawAssistanceEnabled(drawStatusProcessor.value, drawHeadingModeProcessor.value)

    private fun isSupportedActiveTrackMode(activeTrackMode: ActiveTrackMode): Boolean {
        return ActiveTrackMode.TRACE == activeTrackMode
                || ActiveTrackMode.QUICK_SHOT == activeTrackMode
                || ActiveTrackMode.SPOTLIGHT == activeTrackMode
                || ActiveTrackMode.SPOTLIGHT_PRO == activeTrackMode
    }

    /**
     * Whether draw assistance is enabled.
     *
     * @param status The vision draw status of the aircraft.
     * @param mode   The heading mode of the camera.
     * @return `true` if draw assistance is enabled, `false` otherwise.
     */
    private fun isDrawAssistanceEnabled(status: VisionDrawStatus, mode: VisionDrawHeadingMode): Boolean {
        val running = VisionDrawStatus.START_AUTO == status
                || VisionDrawStatus.START_MANUAL == status
                || VisionDrawStatus.PAUSE == status
        return !running || VisionDrawHeadingMode.FORWARD == mode
    }

    /**
     * Whether the given FlightMode is an attitude mode.
     *
     * @param state The aircraft's flight mode
     * @return `true` if the aircraft is in an attitude mode, `false` otherwise.
     */
    private fun isAttiMode(state: FlightMode): Boolean {
        return (state == FlightMode.ATTI
                || state == FlightMode.ATTI_COURSE_LOCK
                || state == FlightMode.ATTI_HOVER
                || state == FlightMode.ATTI_LIMITED
                || state == FlightMode.ATTI_LANDING)
    }
    //endregion

    //region States
    /**
     * The status of the vision system.
     */
    enum class VisionSystemStatus {
        /**
         * Obstacle avoidance is disabled by the user.
         */
        CLOSED,

        /**
         * The vision system is not available. This could be due to the flight mode, tap mode,
         * tracking mode, draw status, or hardware failure.
         */
        DISABLED,

        /**
         * The vision system is functioning normally.
         */
        NORMAL,

        /**
         * Product has omnidirectional obstacle avoidance sensors and all vision systems are
         * available.
         */
        OMNI_ALL,

        /**
         * Product has omnidirectional obstacle avoidance sensors and only forward and backward
         * vision systems are available. Left and right vision systems are only available in
         * ActiveTrack mode and Tripod Mode.
         */
        OMNI_FRONT_BACK,

        /**
         * Only horizontal vision sensors are available. Supported by Matrice 300 RTK.
         */
        OMNI_HORIZONTAL,

        /**
         * Only vertical vision sensors are available. Supported by Matrice 300 RTK.
         */
        OMNI_VERTICAL,

        /**
         * Product has omnidirectional obstacle avoidance sensors and the vision system is not
         * available. This could be due to the flight mode, tap mode, tracking mode, draw status,
         * or hardware failure.
         */
        OMNI_DISABLED,

        /**
         * Product has omnidirectional obstacle avoidance sensors and obstacle avoidance is
         * disabled by the user.
         */
        OMNI_CLOSED
    }
    //endregion
}