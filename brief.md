Вот готовый подробный бриф для твоего приложения **«Bio-Focus»**, оптимизированный для загрузки в **Claude Code**. Этот бриф составлен так, чтобы ИИ сразу понял технические ограничения платформы Wear OS 2.26 (API 28), архитектуру приложения и логику работы фоновых сервисов.

## ---

**ARCHITECTURE & SPECIFICATION BRIEF: "Bio-Focus" for Wear OS 2.26**

## **1\. Project Overview**

> * **Project Name:** Bio-Focus  
> * **Target Device:** TicWatch Pro 3 GPS (Dual Display 2.0: FSTN \+ AMOLED)  
> * **Target OS:** Wear OS 2.26 (**Android 9.0, API Level 28**)  
> * **Development Language:** Kotlin  
> * **UI Framework:** Classical Android Views (XML) \+ Wearable Support Library *(Note: Jetpack Compose for Wear OS is not supported on API 28\)*  
> * **AI Tooling:** Claude Code (for codebase generation and maintenance)

## **2\. Core Value Proposition**

Bio-Focus is an active bio-assistant for productivity that operates in real-time. Unlike passive fitness trackers that only collect data for historical charts, Bio-Focus monitors the user's physiological and physical states to send **immediate, actionable interventions** directly on the watch.

## **3\. Key Feature Set & Logic (MVP)**

The application operates on three primary triggers:

> * **Sedentary Alert (Step Tracker):**  
  * The app monitors physical activity in the background.  
  * If the user takes **0 steps over a rolling 45-minute window** during active hours, the app independently triggers and sends a local notification: *"You've been sitting too long. Stand up and stretch\!"*.  
> * **Stress Intervention (HRV Spike):**  
  * Monitors Heart Rate Variability (HRV) during focus sessions.  
  * If a sudden drop in HRV indicates a sharp stress spike, the watch vibrates and pushes a relaxation prompt: *"Stress detected. Breathe with me for 1 minute."*  
> * **Fatigue / Drowsiness Detection (HR & SpO2 Drop):**  
  * Detects when the user is falling asleep or experiencing extreme cognitive fatigue during work hours.  
  * Triggers a high-intensity vibration and a command: *"Focus dropping. Stand up or drink water\!"*

## **4\. Hardware & System API Integration**

| Hardware / API Component | Implementation Purpose | Wear OS 2.26 Specifics |
| :---- | :---- | :---- |
| **FSTN / Ambient Mode** | Low-power state representation | Use AmbientModeSupport.AmbientCallback to render critical focus states when the AMOLED screen dims. |
| **Step Counter Sensor** | Sedentary tracking | Sensor.TYPE\_STEP\_COUNTER via Android SensorManager. |
| **Heart Rate Sensor** | HRV & Fatigue tracking | Sensor.TYPE\_HEART\_RATE with foreground service permissions. |
| **Local Notification API** | Autonomous reminders | Standard Android NotificationCompat.Builder targeting Wear OS notification channels. |
| **Background Services** | Continuous tracking | A sticky Foreground Service with a persistent notification to prevent the Wear OS system from killing the app process. |

## **5\. Technical Constraints & Architecture for Claude Code**

> * **No Jetpack Compose:** All layouts must use XML with BoxInsetLayout or WearableRecyclerView to support round watch screens properly.  
> * **Battery Optimization:** Step counting must rely on hardware batching (registerListener with a maxReportLatencyUs delay) so the CPU can sleep between updates.  
> * **Notification Autonomy:** Notifications must be generated and handled **locally on the watch** via NotificationManager, completely independent of any companion phone app.  
> * **Permissions:** Must handle runtime requests for android.permission.BODY\_SENSORS and android.permission.FOREGROUND\_SERVICE explicitly.

## **6\. Project Directory Structure (Target)**

`app/`  
`├── src/main/`  
`│   ├── java/com/biofocus/wear/`  
`│   │   ├── service/`  
`│   │   │   ├── BioTrackingService.kt (Foreground service for HRV & Steps)`  
`│   │   │   └── NotificationReceiver.kt (Triggers local notifications)`  
`│   │   ├── ui/`  
`│   │   │   ├── MainActivity.kt (Setup, permissions, and session configuration)`  
`│   │   │   └── FocusAmbientActivity.kt (Handles low-power FSTN screen state)`  
`│   │   └── utils/`  
`│   │       └── BioMath.kt (Algorithm for calculating stress indexes from HRV)`  
`│   ├── res/`  
`│   │   ├── layout/ (XML layouts for round screens)`  
`│   │   └── values/ (Wear OS specific scaling and themes)`  
`│   └── AndroidManifest.xml`  
`└── build.gradle (Configured for minSdkVersion 28 / targetSdkVersion 28)`

---

Этот бриф готов к тому, чтобы ты скопировал его и скормил **Claude Code** как системный промпт. С чего начнем реализацию? Я могу сгенерировать для тебя **код фонового сервиса (BioTrackingService.kt)**, который будет отвечать за подсчет шагов и отправку локальных уведомлений, или подготовить **build.gradle и AndroidManifest.xml** со всеми нужными зависимостями под Wear OS 2.26. В какой файл погружаемся?