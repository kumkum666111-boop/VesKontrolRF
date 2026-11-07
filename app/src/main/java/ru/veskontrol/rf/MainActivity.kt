package ru.veskontrol.rf

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.min

private const val CHANNEL_ID = "veskontrol_axle_warnings"
private const val PREFS = "veskontrol_v4"

private val Lime = Color(0xFFB7F36B)
private val Background = Color(0xFF0B1016)
private val Panel = Color(0xFF171F29)
private val Muted = Color(0xFF9AA7B5)
private val Warning = Color(0xFFFFC266)
private val Danger = Color(0xFFFF7272)
private val White = Color(0xFFEAF0F5)

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private var vehicle by mutableStateOf(VehicleType.SOLO_TRUCK)
    private var roadCategory by mutableStateOf(RoadCategory.UNKNOWN)
    private var roadNorm by mutableStateOf<RoadNorm?>(null)
    private var axleCount by mutableIntStateOf(2)
    private var roadSignAxleLimit by mutableStateOf("")
    private var actualGross by mutableStateOf("")
    private var additionalGrossLimit by mutableStateOf("")
    private var result by mutableStateOf<AxleCalculation?>(null)
    private var massState by mutableStateOf(LoadState.NOT_ENTERED)
    private var massEffectiveLimit by mutableStateOf<Double?>(null)
    private var massSource by mutableStateOf("")
    private var measuredGrossUsed by mutableStateOf<Double?>(null)
    private var measuredGrossSource by mutableStateOf("")
    private var activeTab by mutableIntStateOf(0)
    private var showVehicleMenu by mutableStateOf(false)
    private var showCategoryMenu by mutableStateOf(false)
    private val axles = mutableStateListOf<AxleInput>()
    private var pendingNotification by mutableStateOf<String?>(null)

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val message = pendingNotification
        pendingNotification = null
        if (granted && message != null) postAlert(message)
        else if (!granted) Toast.makeText(this, "Разрешение на уведомления не выдано", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        restore()
        setContent { AppUi() }
    }

    private fun restore() {
        vehicle = runCatching { VehicleType.valueOf(prefs.getString("vehicle", vehicle.name)!!) }.getOrDefault(vehicle)
        roadCategory = runCatching { RoadCategory.valueOf(prefs.getString("roadCategory", "UNKNOWN")!!) }.getOrDefault(RoadCategory.UNKNOWN)
        roadNorm = prefs.getString("roadNorm", null)?.let { key -> runCatching { RoadNorm.valueOf(key) }.getOrNull() }
        axleCount = prefs.getInt("axleCount", 2).coerceIn(1, 12)
        roadSignAxleLimit = prefs.getString("roadSignAxleLimit", "") ?: ""
        actualGross = prefs.getString("actualGross", "") ?: ""
        additionalGrossLimit = prefs.getString("additionalGrossLimit", "") ?: ""
        axles.clear()
        repeat(axleCount) { i ->
            axles.add(
                AxleInput(
                    actualLoad = prefs.getString("axle_${i}_actual", "") ?: "",
                    wheelCount = prefs.getInt("axle_${i}_wheelCount", 2),
                    dualTyres = prefs.getBoolean("axle_${i}_dual", false),
                    gapFromPrevious = prefs.getString("axle_${i}_gap", "") ?: "",
                    ownLimit = prefs.getString("axle_${i}_ownLimit", "") ?: ""
                )
            )
        }
    }

    private fun persist() {
        prefs.edit()
            .putString("vehicle", vehicle.name)
            .putString("roadCategory", roadCategory.name)
            .putString("roadNorm", roadNorm?.name)
            .putInt("axleCount", axleCount)
            .putString("roadSignAxleLimit", roadSignAxleLimit)
            .putString("actualGross", actualGross)
            .putString("additionalGrossLimit", additionalGrossLimit)
            .apply()
        axles.forEachIndexed { i, axle ->
            prefs.edit()
                .putString("axle_${i}_actual", axle.actualLoad)
                .putInt("axle_${i}_wheelCount", axle.wheelCount)
                .putBoolean("axle_${i}_dual", axle.dualTyres)
                .putString("axle_${i}_gap", axle.gapFromPrevious)
                .putString("axle_${i}_ownLimit", axle.ownLimit)
                .apply()
        }
    }

    private fun changeAxleCount(newCount: Int) {
        axleCount = newCount.coerceIn(1, 12)
        while (axles.size < axleCount) axles.add(AxleInput())
        while (axles.size > axleCount) axles.removeAt(axles.lastIndex)
        result = null
        persist()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Контроль осевой нагрузки",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Предупреждения при приближении к заданной допустимой нагрузке и при превышении"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notifyIfNeeded(message: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotification = message
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            postAlert(message)
        }
    }

    private fun postAlert(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("ВесКонтроль РФ — внимание")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(4100, notification)
    }

    private fun calculate() {
        persist()
        val signText = roadSignAxleLimit.trim()
        val sign = AxleRules.parseNumber(signText)
        if (signText.isNotEmpty() && sign == null) {
            Toast.makeText(this, "Проверьте число в поле ограничения по дорожному знаку.", Toast.LENGTH_LONG).show()
            return
        }
        if (actualGross.isNotBlank() && AxleRules.parseNumber(actualGross) == null) {
            Toast.makeText(this, "Проверь фактическую полную массу.", Toast.LENGTH_LONG).show()
            return
        }
        if (additionalGrossLimit.isNotBlank() && (AxleRules.parseNumber(additionalGrossLimit) == null || AxleRules.parseNumber(additionalGrossLimit)!! <= 0.0)) {
            Toast.makeText(this, "Проверь дополнительный предел полной массы.", Toast.LENGTH_LONG).show()
            return
        }
        val calculation = AxleRules.calculate(axles.toList(), roadNorm, sign)
        result = calculation
        if (calculation.errors.isNotEmpty()) {
            Toast.makeText(this, calculation.errors.joinToString("\n"), Toast.LENGTH_LONG).show()
            return
        }

        val statutoryMass = AxleRules.statutoryGrossMassLimit(vehicle, axleCount).tonnes
        val customMass = AxleRules.parseNumber(additionalGrossLimit)
        massEffectiveLimit = when {
            statutoryMass != null && customMass != null -> min(statutoryMass, customMass)
            statutoryMass != null -> statutoryMass
            customMass != null -> customMass
            else -> null
        }
        massSource = when {
            statutoryMass != null && customMass != null && customMass < statutoryMass ->
                "Применён более строгий введённый предел (ПТС/СТС, знак или разрешение)."
            statutoryMass != null -> "Приложение № 3 к Постановлению № 2060; учитывайте более строгие знаки и документы."
            customMass != null -> "Предел введён вручную по документам/знаку."
            else -> "Для этого вида ТС внесите разрешённую максимальную массу из ПТС/СТС или документов."
        }
        val manualGross = AxleRules.parseNumber(actualGross)
        val summedAxlesGross = AxleRules.sumAxleLoads(axles.toList())
        measuredGrossUsed = manualGross ?: summedAxlesGross
        measuredGrossSource = when {
            manualGross != null -> "значение введено вручную"
            summedAxlesGross != null -> "рассчитано как сумма нагрузок всех осей"
            else -> "нет полного измерения"
        }
        massState = AxleRules.evaluateMass(measuredGrossUsed, massEffectiveLimit)

        val overloadedAxes = calculation.axes.filter { it.state == LoadState.OVER }
        val overloadedGroups = calculation.groups.filter { it.state == LoadState.OVER && it.effectiveGroupLimit != null }
        val near = calculation.axes.filter { it.state == LoadState.NEAR_LIMIT }
        val nearGroups = calculation.groups.filter { it.state == LoadState.NEAR_LIMIT }
        val massOver = massState == LoadState.OVER
        when {
            overloadedAxes.isNotEmpty() || overloadedGroups.isNotEmpty() || massOver -> {
                val text = buildString {
                    append("Перегруз: ")
                    if (overloadedAxes.isNotEmpty()) append("оси ${overloadedAxes.joinToString { it.axleNumber.toString() }}. ")
                    if (overloadedGroups.isNotEmpty()) append("группы ${overloadedGroups.joinToString { it.groupNumber.toString() }}. ")
                    if (massOver) append("Превышена полная масса.")
                    append(" Не начинайте движение до устранения превышения и проверки документов.")
                }
                notifyIfNeeded(text)
            }
            near.isNotEmpty() || nearGroups.isNotEmpty() || massState == LoadState.NEAR_LIMIT -> {
                notifyIfNeeded("Нагрузка близка к заданному пределу (90% и более). Перепроверьте распределение груза и результаты взвешивания.")
            }
        }
    }

    @Composable
    private fun AppUi() {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Lime,
                onPrimary = Color(0xFF17210D),
                background = Background,
                surface = Panel,
                onSurface = White,
                secondary = Lime
            )
        ) {
            Scaffold(
                containerColor = Background,
                bottomBar = {
                    NavigationBar(containerColor = Panel) {
                        listOf("Калькулятор", "Результат", "Справка").forEachIndexed { index, label ->
                            NavigationBarItem(
                                selected = activeTab == index,
                                onClick = { activeTab = index },
                                icon = { Text(listOf("⚖", "▤", "i")[index], fontWeight = FontWeight.Bold) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            ) { padding ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text("ВЕСКОНТРОЛЬ РФ", color = Lime, fontWeight = FontWeight.Black)
                    Text(
                        when (activeTab) {
                            0 -> "Нагрузка на оси и масса"
                            1 -> "Результат расчёта"
                            else -> "Нормы и последствия"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))
                    when (activeTab) {
                        0 -> CalculatorScreen()
                        1 -> ResultScreen()
                        else -> InfoScreen()
                    }
                }
            }
        }
    }

    @Composable
    private fun CalculatorScreen() {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CardPanel {
                Section("Тип транспортного средства")
                Box {
                    OutlinedButton(onClick = { showVehicleMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(vehicle.title, modifier = Modifier.weight(1f))
                        Text("▾")
                    }
                    DropdownMenu(expanded = showVehicleMenu, onDismissRequest = { showVehicleMenu = false }) {
                        VehicleType.values().forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.title) },
                                onClick = { vehicle = option; showVehicleMenu = false; result = null; persist() }
                            )
                        }
                    }
                }
                Section("Категория дороги")
                Box {
                    OutlinedButton(onClick = { showCategoryMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Категория: ${roadCategory.title}", modifier = Modifier.weight(1f))
                        Text("▾")
                    }
                    DropdownMenu(expanded = showCategoryMenu, onDismissRequest = { showCategoryMenu = false }) {
                        RoadCategory.values().forEach { option ->
                            DropdownMenuItem(
                                text = { Text(if (option == RoadCategory.UNKNOWN) "Неизвестна" else "Категория ${option.title}") },
                                onClick = { roadCategory = option; showCategoryMenu = false; result = null; persist() }
                            )
                        }
                    }
                }
                Text(
                    "Категория дороги сама по себе не определяет осевой лимит. Уточните расчётный норматив дороги по данным владельца дороги или знакам ниже. Тип ТС нужен для расчёта полной массы; осевой предел также зависит от схемы колёс и межосевого расстояния.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall
                )
                Section("Нормативная нагрузка дороги")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    RoadNorm.values().forEach { option ->
                        FilterChip(
                            selected = roadNorm == option,
                            onClick = { roadNorm = option; result = null; persist() },
                            label = { Text(option.label, maxLines = 1) }
                        )
                    }
                }
                OutlinedTextField(
                    value = roadSignAxleLimit,
                    onValueChange = { roadSignAxleLimit = decimalInput(it); result = null; persist() },
                    label = { Text("Лимит на ось по знаку, т (если есть)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Text(
                    "Если указали ограничение с дорожного знака, оно имеет приоритет над табличным нормативом. Если знака нет, выберите норматив 6 / 10 / 11,5 т/ось.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            CardPanel {
                Section("Количество осей")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedButton(onClick = { changeAxleCount(axleCount - 1) }, enabled = axleCount > 1) { Text("−") }
                    Text("$axleCount", style = MaterialTheme.typography.headlineMedium, color = Lime, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { changeAxleCount(axleCount + 1) }, enabled = axleCount < 12) { Text("+") }
                    Text("осей всего по ТС / автопоезду", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    if (vehicle == VehicleType.ROAD_TRAIN)
                        "Для автопоезда укажите общее число осей тягача, полуприцепа/прицепа."
                    else
                        "Для прицепа как части автопоезда считайте оси всего состава. Для отдельного прицепа используйте его данные из документов.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            axles.forEachIndexed { index, axle ->
                CardPanel {
                    Section(axleTitle(index + 1))
                    OutlinedTextField(
                        value = axle.actualLoad,
                        onValueChange = { axles[index] = axle.copy(actualLoad = decimalInput(it)); result = null; persist() },
                        label = { Text("Фактическая нагрузка, т") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    OutlinedTextField(
                        value = axle.ownLimit,
                        onValueChange = { axles[index] = axle.copy(ownLimit = decimalInput(it)); result = null; persist() },
                        label = { Text("Предел по документам / изготовителю, т") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    Text("Это ограничение не заменяет табличный дорожный предел: приложение применяет меньшее из двух.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Колёс на оси по таблице", color = Muted, modifier = Modifier.weight(1f))
                        ChoicePill(
                            label = "2",
                            selected = axle.wheelCount == 2,
                            onClick = { axles[index] = axle.copy(wheelCount = 2); result = null; persist() }
                        )
                        Spacer(Modifier.width(6.dp))
                        ChoicePill(
                            label = "4+",
                            selected = axle.wheelCount >= 4,
                            onClick = { axles[index] = axle.copy(wheelCount = 4); result = null; persist() }
                        )
                    }
                    Text(
                        "Выберите 2 или 4+ колеса на оси. Односкатные/двускатные — колесо с одной или двумя шинами соответственно.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Шины на колесе", color = Muted, modifier = Modifier.weight(1f))
                        ChoicePill("1 шина", !axle.dualTyres) {
                            axles[index] = axle.copy(dualTyres = false); result = null; persist()
                        }
                        Spacer(Modifier.width(5.dp))
                        ChoicePill("2 шины", axle.dualTyres) {
                            axles[index] = axle.copy(dualTyres = true); result = null; persist()
                        }
                    }
                    if (axle.wheelCount >= 4) {
                        Text("Для группы с 4+ колёсами на каждой оси используется отдельная строка таблицы без деления на односкатные/двускатные значения.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (index > 0) {
                        OutlinedTextField(
                            value = axle.gapFromPrevious,
                            onValueChange = { axles[index] = axle.copy(gapFromPrevious = decimalInput(it)); result = null; persist() },
                            label = { Text("Расстояние от оси ${index} до этой, м") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                    } else {
                        Text("Первая ось состава.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            CardPanel {
                Section("Полная масса транспортного средства")
                OutlinedTextField(
                    value = actualGross,
                    onValueChange = { actualGross = decimalInput(it); result = null; persist() },
                    label = { Text("Фактическая полная масса, т (необязательно)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = additionalGrossLimit,
                    onValueChange = { additionalGrossLimit = decimalInput(it); result = null; persist() },
                    label = { Text("Более строгий предел по ПТС/СТС/знаку, т") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Text("Можно не вводить фактическую полную массу отдельно: приложение сложит все нагрузки по осям, если они заполнены.", color = Muted, style = MaterialTheme.typography.bodySmall)
                val auto = AxleRules.statutoryGrossMassLimit(vehicle, axleCount)
                Text(
                    auto.tonnes?.let { "Предел по таблице Постановления № 2060: ${fmt(it)} т" }
                        ?: auto.sourceLabel,
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Button(
                onClick = { calculate(); activeTab = 1 },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp)
            ) {
                Text("РАССЧИТАТЬ ДОПУСТИМУЮ НАГРУЗКУ", fontWeight = FontWeight.Bold)
            }
            Text(
                "Расчёт справочный. Всегда применяйте меньшее из ограничений: норматив дороги, дорожный знак, ПТС/СТС, специальное разрешение и требования для конкретного маршрута.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    @Composable
    private fun ResultScreen() {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val calc = result
            if (calc == null) {
                CardPanel {
                    Text("Расчёт ещё не выполнен", color = White, fontWeight = FontWeight.Bold)
                    Text("Заполните фактические нагрузки, расстояния между соседними осями и норматив дороги, затем нажмите «Рассчитать».", color = Muted)
                    Button(onClick = { activeTab = 0 }) { Text("Перейти к калькулятору") }
                }
                return@Column
            }
            if (calc.errors.isNotEmpty()) {
                CardPanel {
                    Text("Нужны дополнительные данные", color = Warning, fontWeight = FontWeight.Bold)
                    calc.errors.forEach { Text("• $it", color = White) }
                }
                return@Column
            }

            val over = calc.axes.filter { it.state == LoadState.OVER }
            val overGroups = calc.groups.filter { it.state == LoadState.OVER && it.effectiveGroupLimit != null }
            val near = calc.axes.filter { it.state == LoadState.NEAR_LIMIT }
            val grossOver = massState == LoadState.OVER
            val hasOver = over.isNotEmpty() || overGroups.isNotEmpty() || grossOver
            val hasNear = near.isNotEmpty() || massState == LoadState.NEAR_LIMIT ||
                calc.groups.any { it.state == LoadState.NEAR_LIMIT }
            val hasMissingMeasuredData = calc.axes.any { it.state == LoadState.NOT_ENTERED } ||
                massState == LoadState.NOT_ENTERED || calc.groups.any { it.state == LoadState.NOT_ENTERED }
            val needsRegistrationAxleLimits = vehicle == VehicleType.PASSENGER_CAR || vehicle == VehicleType.TRAILER_ONLY
            val missingRegistrationAxleLimits = needsRegistrationAxleLimits && axles.any { AxleRules.parseNumber(it.ownLimit) == null }
            val incomplete = hasMissingMeasuredData || missingRegistrationAxleLimits

            CardPanel {
                Text(
                    when {
                        hasOver -> "ПЕРЕГРУЗ ОБНАРУЖЕН"
                        hasNear -> "ВНИМАНИЕ: БЛИЗКО К ПРЕДЕЛУ"
                        incomplete -> "ДАННЫХ НЕДОСТАТОЧНО"
                        else -> "ПРЕДВАРИТЕЛЬНО В НОРМЕ"
                    },
                    color = when {
                        hasOver -> Danger
                        hasNear || incomplete -> Warning
                        else -> Lime
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black
                )
                Text(
                    when {
                        hasOver -> "Не начинайте движение с превышением. Перераспределите или уменьшите груз и повторите контрольное взвешивание."
                        hasNear -> "Есть значения на уровне 90% или выше от предела. Перепроверьте весы и распределение груза."
                        incomplete -> "Заполните все фактические нагрузки и полную массу. Для легкового автомобиля или отдельного прицепа обязательно внесите также предел по каждой оси из документов изготовителя."
                        else -> "По введённым значениям превышений не обнаружено. Это не подтверждает соответствие всем ограничениям маршрута."
                    },
                    color = White
                )
            }

            if (missingRegistrationAxleLimits) {
                CardPanel {
                    Text("Нужны данные из документов ТС", color = Warning, fontWeight = FontWeight.Bold)
                    Text("Для легкового автомобиля или отдельного прицепа дорожный норматив сам по себе не является пределом, установленным изготовителем. Заполните поле «Предел по паспорту оси» для каждой оси и сравнивайте с меньшим из двух значений.", color = White)
                }
            }

            CardPanel {
                Text("Дорога: категория ${roadCategory.title}", color = White)
                Text("Применённый осевой норматив: ${roadNorm?.label ?: "ограничение по дорожному знаку"}", color = Muted)
            }

            Section("Проверка каждой оси")
            calc.axes.forEach { axis ->
                CardPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(axleTitle(axis.axleNumber), color = White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        StateBadge(axis.state)
                    }
                    Text(
                        "Фактически: ${axis.actual?.let { "${fmt(it)} т" } ?: "не введено"}",
                        color = Muted
                    )
                    Text("Нормативный предел: ${fmt(axis.statutoryLimit)} т", color = White)
                    if (axis.effectiveLimit < axis.statutoryLimit) {
                        Text("С учётом более строгого лимита ТС: ${fmt(axis.effectiveLimit)} т", color = Warning)
                    }
                    axis.actual?.let {
                        Text("Запас: ${fmt(axis.effectiveLimit - it)} т", color = if (it > axis.effectiveLimit) Danger else Lime)
                    }
                    Text("Группа осей: ${axis.groupNumber}", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }

            Section("Проверка сближенных групп")
            calc.groups.forEach { group ->
                CardPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (group.firstAxle == group.lastAxle) "Ось ${group.firstAxle}" else "Группа ${group.firstAxle}–${group.lastAxle}",
                            color = White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                        )
                        StateBadge(group.state)
                    }
                    group.spacingAverage?.let { Text("Средний межосевой интервал: ${fmt(it)} м", color = Muted) }
                    group.actualTotal?.let { Text("Суммарная фактическая нагрузка: ${fmt(it)} т", color = White) }
                    group.effectiveGroupLimit?.let { limit ->
                        Text("Допустимая суммарная нагрузка: ${fmt(limit)} т", color = Lime, fontWeight = FontWeight.Bold)
                        group.actualTotal?.let { Text("Запас по группе: ${fmt(limit - it)} т", color = if (it > limit) Danger else Lime) }
                    } ?: Text("Отдельный суммарный лимит группы в этой строке таблицы не установлен; проверяйте каждую ось по отдельности.", color = Muted)
                    Text(group.note, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }

            Section("Полная масса")
            CardPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Полная масса", color = White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    StateBadge(massState)
                }
                Text("Фактически: ${measuredGrossUsed?.let { "${fmt(it)} т" } ?: "не введено"}", color = Muted)
                Text("Источник значения: $measuredGrossSource", color = Muted, style = MaterialTheme.typography.bodySmall)
                Text("Применённый предел: ${massEffectiveLimit?.let { "${fmt(it)} т" } ?: "не задан"}", color = Lime)
                Text(massSource, color = Muted, style = MaterialTheme.typography.bodySmall)
                if (hasOver) {
                    Text(
                        "Перегруз может повлечь административную ответственность, возмещение вреда дороге, задержку рейса и необходимость перегрузки. Размер и вид санкции зависят от применимого нормативного ограничения, наличия специального разрешения и обстоятельств нарушения; приложение не рассчитывает штраф по пользовательским значениям из ПТС/СТС.",
                        color = Danger
                    )
                }
                Text(
                    "Порог 90% — предупредительный порог приложения, а не самостоятельное нарушение закона.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (hasOver || hasNear) {
                CardPanel {
                    Section("Что делать")
                    Text("1. Не продолжайте движение, пока не устранено превышение.", color = White)
                    Text("2. Проверьте калибровку весов и повторите измерение на ровной площадке.", color = White)
                    Text("3. Перераспределите груз по осям или уменьшите его массу в соответствии с документами и условиями перевозки.", color = White)
                    Text("4. При необходимости оформите специальное разрешение и согласуйте маршрут до выезда.", color = White)
                }
            }
            Button(onClick = { activeTab = 0 }, modifier = Modifier.fillMaxWidth()) { Text("Изменить данные") }
            Spacer(Modifier.height(16.dp))
        }
    }

    @Composable
    private fun InfoScreen() {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CardPanel {
                Section("Откуда берутся лимиты")
                Text("Основной источник: Постановление Правительства РФ от 01.12.2023 № 2060.", color = White)
                Text("Приложение № 2 — допустимая нагрузка на одиночные оси и группы сближенных осей. Приложение № 3 — допустимая полная масса в зависимости от типа и количества осей.", color = Muted)
                Text("В скобках в таблице приведены значения для двускатных колёс. Для смешанных групп применяется более строгая строка для односкатных колёс. Для групп из 2 или 3 осей проверяется общая нагрузка группы и нагрузка каждой отдельной оси.", color = Muted)
                Text("Официальный норматив дороги выбирается отдельно: 6, 10 или 11,5 т/ось. Категория I–V не всегда однозначно определяет фактический лимит конкретного участка.", color = Warning)
                Button(onClick = { openUrl("https://www.consultant.ru/document/cons_doc_LAW_463407/") }) {
                    Text("Открыть Постановление № 2060")
                }
            }
            CardPanel {
                Section("Последствия перегруза")
                Text("• Повышенная нагрузка ускоряет разрушение дорожного покрытия и увеличивает воздействие на мосты и другие сооружения.", color = White)
                Text("• Перегруженные шины, ступицы, подвеска и элементы рамы могут перегреваться и повреждаться.", color = White)
                Text("• Меняется управляемость, распределение тормозных усилий и устойчивость автопоезда; увеличивается риск потери контроля.", color = White)
                Text("• Возможны задержка перевозки, необходимость перегрузки/перераспределения груза, возмещение вреда дороге и административная ответственность.", color = White)
            }
            CardPanel {
                Section("Ответственность")
                Text("По статье 12.21.1 КоАП РФ для предусмотренных законом случаев превышения допустимой массы или нагрузки на ось без специального разрешения либо сверх разрешения предусмотрены штрафы собственнику/владельцу ТС:", color = White)
                Text("• Более 10%, но не более 20% — 300 000 ₽.", color = Warning)
                Text("• Более 20%, но не более 50% — 450 000 ₽.", color = Danger)
                Text("• Более 50% — 600 000 ₽.", color = Danger)
                Text("Это не персональная юридическая консультация: ответственность зависит от фактического состава нарушения, разрешения, способа фиксации и действующей редакции закона.", color = Muted, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { openUrl("https://www.consultant.ru/document/cons_doc_LAW_34661/f727c535f35518ba16c7d51b782a5f6ed67b76a3/") }) {
                    Text("Открыть статью 12.21.1 КоАП")
                }
            }
            CardPanel {
                Section("Как пользоваться")
                Text("1. Выберите тип ТС и внесите общее число осей состава.", color = White)
                Text("2. Выберите категорию дороги для справки и норматив 6/10/11,5 т/ось по данным дороги.", color = White)
                Text("3. Для каждой оси внесите взвешенную нагрузку, схему колёс и расстояние до предыдущей оси.", color = White)
                Text("4. Если на участке есть знак с отдельным ограничением нагрузки на ось, укажите его значение — оно имеет приоритет.", color = White)
                Text("5. Внесите полную массу и более строгий лимит из ПТС/СТС, разрешения или знака.", color = White)
                Text("6. Рассчитайте результат и устраните любые превышения до движения.", color = White)
            }
            CardPanel {
                Section("Важное ограничение")
                Text("Программа рассчитывает пределы по нормативной таблице. Она не знает автоматически о временных ограничениях, состоянии конкретного моста, специальных разрешениях и новых знаках на маршруте. Перед поездкой проверьте данные владельца дороги и документы на транспорт.", color = Warning)
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    @Composable
    private fun CardPanel(content: @Composable ColumnScope.() -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Panel)
        ) {
            Column(
                Modifier.padding(13.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content
            )
        }
    }

    @Composable
    private fun Section(text: String) {
        Text(text, color = Lime, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }

    @Composable
    private fun ChoicePill(label: String, selected: Boolean, onClick: () -> Unit) {
        FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
    }

    @Composable
    private fun StateBadge(state: LoadState) {
        val (label, color) = when (state) {
            LoadState.NOT_ENTERED -> "Нет данных" to Muted
            LoadState.OK -> "Норма" to Lime
            LoadState.NEAR_LIMIT -> "90%+" to Warning
            LoadState.OVER -> "ПЕРЕГРУЗ" to Danger
        }
        Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(30.dp)) {
            Text(label, color = color, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontWeight = FontWeight.Bold)
        }
    }

    private fun axleTitle(number: Int): String = when (vehicle) {
        VehicleType.PASSENGER_CAR, VehicleType.SOLO_TRUCK, VehicleType.BUS -> when (number) {
            1 -> "Передняя ось"
            2 -> "Задняя ось"
            else -> "Задняя ось ${number - 1}"
        }
        VehicleType.ROAD_TRAIN -> if (number == 1) "Передняя ось тягача" else "Ось состава $number"
        VehicleType.TRAILER_ONLY -> "Ось прицепа $number"
        VehicleType.ARTICULATED_BUS -> "Ось автобуса $number"
        VehicleType.OTHER -> "Ось $number"
    }

    private fun decimalInput(text: String): String =
        text.filter { it.isDigit() || it == '.' || it == ',' }.take(12)

    private fun fmt(value: Double): String =
        String.format(Locale("ru", "RU"), "%.2f", value).trimEnd('0').trimEnd(',')

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        persist()
        super.onPause()
    }
}
