package de.wrch.finanzplaner

import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

enum class Frequency { MONTHLY, QUARTERLY, YEARLY, ONCE }
enum class Source { BANK, CASH }

data class Income(val id:String=id(), var amount:Double=0.0, var date:String=LocalDate.now().toString(), var note:String="", var recurring:Boolean=false, var templateId:String?=null)
data class FixedOverride(var name:String="", var amount:Double=0.0, var startDate:String=LocalDate.now().toString(), var frequency:Frequency=Frequency.MONTHLY)
data class FixedItem(val id:String=id(), var name:String="", var amount:Double=0.0, var startDate:String=LocalDate.now().toString(), var frequency:Frequency=Frequency.MONTHLY, val overrides:MutableMap<String,FixedOverride> = mutableMapOf())
data class FixedCategory(val id:String=id(), var name:String="Wohnen", var icon:String="🏠", var color:Long=0xFF4F7F63, val items:MutableList<FixedItem> = mutableListOf())
data class BudgetCategory(val id:String=id(), var name:String="Lebensmittel & Haushalt", var icon:String="🛒", var color:Long=0xFF4F7F63)
data class SavingPot(val id:String=id(), var name:String="Notgroschen", var icon:String="🛟", var color:Long=0xFF4F7F63, var percent:Int=0)
data class Expense(val id:String=id(), var budgetId:String="", var amount:Double=0.0, var date:String=LocalDate.now().toString(), var source:Source=Source.CASH, var note:String="")
data class Transfer(val id:String=id(), var amount:Double=0.0, var date:String=LocalDate.now().toString(), var note:String="Bank → Bargeld")
data class SavingMove(val id:String=id(), var savingId:String="", var amount:Double=0.0, var date:String=LocalDate.now().toString(), var note:String="")
data class ArchiveSnapshot(
    var incomeTotal:Double=0.0, var fixedReserved:Double=0.0, var expensesTotal:Double=0.0, var closingRest:Double=0.0,
    var details:String=""
)
data class MonthData(
    val income:MutableList<Income> = mutableListOf(),
    val allocations:MutableMap<String,Double> = mutableMapOf(),
    val expenses:MutableList<Expense> = mutableListOf(),
    val transfers:MutableList<Transfer> = mutableListOf(),
    val savingMoves:MutableList<SavingMove> = mutableListOf(),
    var openingBalance:Double=0.0,
    var closed:Boolean=false,
    var closedAt:Long?=null,
    var archive:ArchiveSnapshot?=null
)

data class AppData(
    val fixed:MutableList<FixedCategory> = defaultFixed(),
    val budgets:MutableList<BudgetCategory> = defaultBudgets(),
    val savings:MutableList<SavingPot> = defaultSavings(),
    val months:MutableMap<String,MonthData> = mutableMapOf(),
    var darkMode:Boolean=false
)

fun id() = UUID.randomUUID().toString()
fun monthKey(date:LocalDate=LocalDate.now()) = "%04d-%02d".format(date.year,date.monthValue)
fun monthKey(ym:YearMonth) = "%04d-%02d".format(ym.year,ym.monthValue)

private val palette = listOf(0xFF4F7F63,0xFFC87B62,0xFFD5AA48,0xFF6C87B8,0xFFA36F9F,0xFF6D9F9B,0xFFA87655,0xFF7E8B62,0xFFB76666,0xFF5F8F72)
fun defaultFixed():MutableList<FixedCategory> {
    val names = listOf("Wohnen" to "🏠","Versicherungen" to "🛡️","Telefon & Internet" to "📱","Streaming & Abos" to "📺","Sport & Freizeit" to "🏋️","Finanzierung & Kredite" to "💳","Familie" to "👨‍👩‍👧","Sparen & Vorsorge" to "🌱")
    return names.mapIndexed { i,(n,ic) -> FixedCategory(name=n,icon=ic,color=palette[i%palette.size]) }.toMutableList()
}
fun defaultBudgets():MutableList<BudgetCategory> {
    val names = listOf("Lebensmittel & Haushalt" to "🛒","Tanken / Auto" to "⛽","Essen / Bestellen / Bäcker" to "🍽️","Freizeit & Unternehmungen" to "🎉","Kleidung & Schuhe" to "👕","Kinderausgaben" to "🧒","Friseur / Körperpflege" to "✂️","Shopping & persönliche Wünsche" to "🛍️","Geschenke & Anlässe" to "🎁","Sonstiger Puffer" to "💶")
    return names.mapIndexed { i,(n,ic) -> BudgetCategory(name=n,icon=ic,color=palette[i%palette.size]) }.toMutableList()
}
fun defaultSavings():MutableList<SavingPot> = mutableListOf(
    SavingPot(name="Notgroschen",icon="🛟",color=palette[0],percent=40),
    SavingPot(name="Langfristiges Sparen",icon="🌱",color=palette[1],percent=30),
    SavingPot(name="Wünsche / Urlaub",icon="✈️",color=palette[2],percent=20),
    SavingPot(name="Auto",icon="🚗",color=palette[3],percent=10)
)
