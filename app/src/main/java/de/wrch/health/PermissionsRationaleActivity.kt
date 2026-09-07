package de.wrch.health

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class PermissionsRationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            setPadding(48,80,48,48)
            textSize = 18f
            text = """WRCH Health-Datenschutz

WRCH verwendet Health Connect nur für Funktionen, die du aktiv freigibst.

In der ersten Version liest WRCH:
• Schritte
• Körpergewicht
• Trainingseinheiten

Die Daten sollen in deinem WRCH-Konto gespeichert werden, damit Dashboard und freiwillige Rankings funktionieren. Du kannst Health-Connect-Berechtigungen jederzeit in Android widerrufen.

Vor einer Veröffentlichung wird diese lokale Erklärung durch die vollständige WRCH-Datenschutzerklärung ersetzt."""
        })
    }
}
