package de.wrch.finanzplaner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Store(context:Context){
    private val prefs=context.getSharedPreferences("wrch_finanzplaner_native",Context.MODE_PRIVATE)
    fun load():AppData = runCatching { decode(JSONObject(prefs.getString("data",null) ?: return AppData())) }.getOrElse { AppData() }
    fun save(d:AppData){ prefs.edit().putString("data",encode(d).toString()).apply() }

    private fun encode(d:AppData)=JSONObject().apply{
        put("darkMode",d.darkMode)
        put("fixed",JSONArray().apply{d.fixed.forEach{c->put(JSONObject().apply{put("id",c.id);put("name",c.name);put("icon",c.icon);put("color",c.color);put("items",JSONArray().apply{c.items.forEach{x->put(JSONObject().apply{put("id",x.id);put("name",x.name);put("amount",x.amount);put("startDate",x.startDate);put("frequency",x.frequency.name);put("overrides",JSONObject().apply{x.overrides.forEach{(k,v)->put(k,JSONObject().apply{put("name",v.name);put("amount",v.amount);put("startDate",v.startDate);put("frequency",v.frequency.name)})}})})}})})}})
        put("budgets",JSONArray().apply{d.budgets.forEach{x->put(JSONObject().apply{put("id",x.id);put("name",x.name);put("icon",x.icon);put("color",x.color)})}})
        put("savings",JSONArray().apply{d.savings.forEach{x->put(JSONObject().apply{put("id",x.id);put("name",x.name);put("icon",x.icon);put("color",x.color);put("percent",x.percent)})}})
        put("months",JSONObject().apply{d.months.forEach{(k,m)->put(k,JSONObject().apply{
            put("opening",m.openingBalance);put("closed",m.closed);put("closedAt",m.closedAt)
            m.archive?.let{a->put("archive",JSONObject().apply{put("incomeTotal",a.incomeTotal);put("fixedReserved",a.fixedReserved);put("expensesTotal",a.expensesTotal);put("closingRest",a.closingRest);put("details",a.details)})}
            put("income",JSONArray().apply{m.income.forEach{x->put(JSONObject().apply{put("id",x.id);put("amount",x.amount);put("date",x.date);put("note",x.note);put("recurring",x.recurring);put("templateId",x.templateId)})}})
            put("alloc",JSONObject().apply{m.allocations.forEach{(id,v)->put(id,v)}})
            put("expenses",JSONArray().apply{m.expenses.forEach{x->put(JSONObject().apply{put("id",x.id);put("budgetId",x.budgetId);put("amount",x.amount);put("date",x.date);put("source",x.source.name);put("note",x.note)})}})
            put("transfers",JSONArray().apply{m.transfers.forEach{x->put(JSONObject().apply{put("id",x.id);put("amount",x.amount);put("date",x.date);put("note",x.note)})}})
            put("savingMoves",JSONArray().apply{m.savingMoves.forEach{x->put(JSONObject().apply{put("id",x.id);put("savingId",x.savingId);put("amount",x.amount);put("date",x.date);put("note",x.note)})}})
        })}}
    }

    private fun decode(o:JSONObject):AppData{
        val d=AppData(mutableListOf(),mutableListOf(),mutableListOf(),mutableMapOf(),o.optBoolean("darkMode",false))
        o.optJSONArray("fixed")?.let{a->repeat(a.length()){i->val c=a.getJSONObject(i);val fc=FixedCategory(c.optString("id",id()),c.optString("name","Kategorie"),c.optString("icon","📌"),c.optLong("color",0xFF4F7F63), mutableListOf());c.optJSONArray("items")?.let{ia->repeat(ia.length()){j->val x=ia.getJSONObject(j);val fi=FixedItem(x.optString("id",id()),x.optString("name"),x.optDouble("amount"),x.optString("startDate",java.time.LocalDate.now().toString()),runCatching{Frequency.valueOf(x.optString("frequency","MONTHLY"))}.getOrDefault(Frequency.MONTHLY));x.optJSONObject("overrides")?.let{oo->oo.keys().forEach{k->val v=oo.getJSONObject(k);fi.overrides[k]=FixedOverride(v.optString("name"),v.optDouble("amount"),v.optString("startDate"),runCatching{Frequency.valueOf(v.optString("frequency","MONTHLY"))}.getOrDefault(Frequency.MONTHLY))}};fc.items+=fi}};d.fixed+=fc}}
        o.optJSONArray("budgets")?.let{a->repeat(a.length()){i->val x=a.getJSONObject(i);d.budgets+=BudgetCategory(x.optString("id",id()),x.optString("name"),x.optString("icon","💶"),x.optLong("color",0xFF4F7F63))}}
        o.optJSONArray("savings")?.let{a->repeat(a.length()){i->val x=a.getJSONObject(i);d.savings+=SavingPot(x.optString("id",id()),x.optString("name"),x.optString("icon","💰"),x.optLong("color",0xFF4F7F63),x.optInt("percent",0))}}
        o.optJSONObject("months")?.let{mo->mo.keys().forEach{ k->val m=mo.getJSONObject(k);val ar=m.optJSONObject("archive")?.let{ArchiveSnapshot(it.optDouble("incomeTotal"),it.optDouble("fixedReserved"),it.optDouble("expensesTotal"),it.optDouble("closingRest"),it.optString("details"))};val md=MonthData(openingBalance=m.optDouble("opening",0.0),closed=m.optBoolean("closed",false),closedAt=m.optLong("closedAt").takeIf{m.has("closedAt")&&!m.isNull("closedAt")},archive=ar)
            m.optJSONArray("income")?.let{a->repeat(a.length()){i->val x=a.getJSONObject(i);md.income+=Income(x.optString("id",id()),x.optDouble("amount"),x.optString("date"),x.optString("note"),x.optBoolean("recurring",false),x.optString("templateId").takeIf{it.isNotBlank()&&it!="null"})}}
            m.optJSONObject("alloc")?.let{a->a.keys().forEach{id->md.allocations[id]=a.optDouble(id,0.0)}}
            m.optJSONArray("expenses")?.let{a->repeat(a.length()){i->val x=a.getJSONObject(i);md.expenses+=Expense(x.optString("id",id()),x.optString("budgetId"),x.optDouble("amount"),x.optString("date"),runCatching{Source.valueOf(x.optString("source","CASH"))}.getOrDefault(Source.CASH),x.optString("note"))}}
            m.optJSONArray("transfers")?.let{a->repeat(a.length()){i->val x=a.getJSONObject(i);md.transfers+=Transfer(x.optString("id",id()),x.optDouble("amount"),x.optString("date"),x.optString("note","Bank → Bargeld"))}}
            m.optJSONArray("savingMoves")?.let{a->repeat(a.length()){i->val x=a.getJSONObject(i);md.savingMoves+=SavingMove(x.optString("id",id()),x.optString("savingId"),x.optDouble("amount"),x.optString("date"),x.optString("note"))}}
            d.months[k]=md
        }}
        if(d.fixed.isEmpty()) d.fixed+=defaultFixed(); if(d.budgets.isEmpty()) d.budgets+=defaultBudgets(); if(d.savings.isEmpty()) d.savings+=defaultSavings()
        return d
    }
}
