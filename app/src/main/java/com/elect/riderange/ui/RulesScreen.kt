package com.elect.riderange.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect.riderange.rules.BoardRules
import com.elect.riderange.rules.RuleSet
import com.elect.riderange.vehicle.VehicleClass
import com.elect.riderange.rules.Source
import com.elect.riderange.ui.theme.RideColors

@Composable
fun RulesScreen(vm: MainViewModel) {
    val repo = vm.s.rules
    val st by repo.state.collectAsStateWithLifecycle()
    val regs = repo.regulations
    val detectedCode = st.region?.stateCode
    val code = st.manualState ?: detectedCode
    val state = regs.state(code)
    var cityPick by rememberSaveable { mutableStateOf<String?>(null) }
    val cityName = if (st.manualState != null && st.manualState != detectedCode) cityPick else cityPick ?: st.region?.city
    val city = regs.city(code, cityName)
    var picking by remember { mutableStateOf(false) }
    val vehicle by vm.s.ride.vehicle.collectAsStateWithLifecycle()
    val vClass = vehicle?.type?.vehicleClass ?: VehicleClass.KICK_SCOOTER
    val primary = regs.primaryFor(vClass)
    val boardsFirst = primary == "boards"

    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Text(when (primary) {
                "boards" -> "Rules for one-wheel boards"
                "ebike" -> "Rules for e-bikes"
                "eskate" -> "Rules for electric skateboards"
                else -> "Rules for e-scooters & e-bikes"
            }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            vehicle?.let { Text("For your ${it.name} (${it.type.label})", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(6.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2A06))) {
                Column(Modifier.padding(12.dp)) {
                    Text("Not legal advice · reviewed ${state?.reviewed ?: regs.reviewed}", style = MaterialTheme.typography.titleSmall, color = RideColors.Amber)
                    Text(regs.disclaimer, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.clickable { picking = true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(listOfNotNull(state?.name ?: "Pick a state", city?.city).joinToString(" · "),
                    style = MaterialTheme.typography.titleLarge, color = RideColors.OneWay)
                Icon(Icons.Filled.ArrowDropDown, "Choose state", tint = RideColors.OneWay)
            }
            Text(
                when {
                    st.manualState != null -> "Chosen manually. "
                    st.detecting -> "Detecting where you are… "
                    detectedCode != null -> "Detected from your location. "
                    else -> st.error ?: "Waiting for your location. "
                } + "Summary only: check the linked source for the exact wording.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (st.manualState != null) TextButton(onClick = { repo.setManualState(null); cityPick = null }) {
                Icon(Icons.Filled.MyLocation, null); Text("  Use my location")
            }
            if (code != null) {
                val cities = regs.citiesIn(code)
                if (cities.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    cities.forEach { c ->
                        FilterChip(selected = city?.city == c.city, onClick = { cityPick = if (city?.city == c.city) "" else c.city }, label = { Text(c.city) })
                    }
                }
            }
        }
        if (state == null) {
            item { Text("No state selected yet.", style = MaterialTheme.typography.bodyLarge) }
        } else {
            val boards = regs.boards(state)
            if (primary == "eskate") item { EskateCard() }
            if (boardsFirst && boards != null) item { BoardsCard(boards) }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(14.dp)) {
                        if (boardsFirst) Text("E-scooters in ${state.name}", style = MaterialTheme.typography.titleMedium, color = RideColors.OneWay)
                        Text(state.summary, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(6.dp))
                        AssistChip(onClick = {}, label = {
                            Text(if (state.confidence == "verified") "Checked against the statute · ${state.reviewed}" else "Summary · reviewed ${state.reviewed}")
                        })
                    }
                }
            }
            city?.let { c ->
                item { RulesCard("${c.city} (city rules)", RideColors.Amber, c.summary, c.scooter, c.ebike, c.sources) }
            }
            if (primary == "ebike") item { RulesCard("E-bikes (state law)", RideColors.RoundTrip, null, state.ebike, null, emptyList()) }
            item { RulesCard("E-scooters (state law)", RideColors.OneWay, null, state.scooter, null, emptyList()) }
            if (primary != "ebike") item { RulesCard("E-bikes (state law)", RideColors.RoundTrip, null, state.ebike, null, emptyList()) }
            if (!boardsFirst && boards != null) item { BoardsCard(boards) }
            item { SourcesCard("Sources", state.sources) }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Close") } },
            title = { Text("Choose a state") },
            text = {
                LazyColumn(Modifier.height(420.dp)) {
                    items(regs.states.sortedBy { it.name }) { s ->
                        Text(s.name, Modifier.fillMaxWidth().clickable {
                            repo.setManualState(s.code); cityPick = null; picking = false
                        }.padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
                        HorizontalDivider()
                    }
                }
            },
        )
    }
}

@Composable
private fun RulesCard(title: String, accent: Color, summary: String?, rules: RuleSet, ebike: RuleSet?, sources: List<Source>) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = accent)
            summary?.let { Spacer(Modifier.height(4.dp)); Text(it, style = MaterialTheme.typography.bodyLarge) }
            rules.items.forEach { r ->
                Spacer(Modifier.height(8.dp))
                Text(r.topic, style = MaterialTheme.typography.titleSmall, color = accent)
                Text(r.text, style = MaterialTheme.typography.bodyLarge)
            }
            ebike?.items?.forEach { r ->
                Spacer(Modifier.height(8.dp))
                Text("E-bikes: ${r.topic}", style = MaterialTheme.typography.titleSmall, color = RideColors.RoundTrip)
                Text(r.text, style = MaterialTheme.typography.bodyLarge)
            }
            if (sources.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SourceLinks(sources)
            }
        }
    }
}

/** Electric skateboards: no state-by-state dataset yet, so a plain note on how they are usually treated. */
@Composable
private fun EskateCard() {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text("Electric skateboards", style = MaterialTheme.typography.titleLarge, color = Color(0xFFC792EA))
            Spacer(Modifier.height(4.dp))
            Text("Most state codes summarised here don't define electric skateboards: they have no handlebars (so they aren't " +
                "e-scooters) and no pedals (so they aren't e-bikes). Local skateboard and motorized-device ordinances and park/trail " +
                "rules are what usually apply, so check your city. California's \"electrically motorized board\" (Vehicle Code 313.5, " +
                "shown in the board card below for CA) does cover them.", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text("Summary: check your local code", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BoardsCard(b: BoardRules) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text("One-wheel boards (Onewheel, VESC)", style = MaterialTheme.typography.titleLarge, color = Color(0xFFC792EA))
            Spacer(Modifier.height(4.dp))
            Text("Category", style = MaterialTheme.typography.titleSmall, color = Color(0xFFC792EA))
            Text(b.category + if (b.specificallyAddressed) "" else " in state law", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(b.text, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text(if (b.confidence == "verified") "Checked against the statute text" else "Summary: check the source", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (b.sources.isNotEmpty()) { Spacer(Modifier.height(6.dp)); SourceLinks(b.sources) }
        }
    }
}

@Composable
private fun SourcesCard(title: String, sources: List<Source>) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            SourceLinks(sources)
        }
    }
}

@Composable
private fun SourceLinks(sources: List<Source>) {
    val uri = LocalUriHandler.current
    sources.forEach { src ->
        Row(Modifier.fillMaxWidth().clickable { try { uri.openUri(src.url) } catch (_: Exception) {} }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = RideColors.OneWay)
            Spacer(Modifier.height(0.dp))
            Text("  " + src.title, color = RideColors.OneWay, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
