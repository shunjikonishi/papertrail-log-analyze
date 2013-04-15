package models;

import play.api.libs.json.Json.toJson;
import play.api.libs.json.JsValue;

object JqGrid {
	
	def empty = {
		Map(
			"start" -> toJson(1),
			"total" -> toJson(0),
			"page" -> toJson(1),
			"records" -> toJson(0),
			"rows" -> toJson(new Array[JsValue](0))
		);
	}
	
	def data(csv: String) = {
		val rows = csv.split("\n");
		val data = rows.foldLeft[List[Map[String, JsValue]]](List.empty) { (list, row) =>
			val cols = row.split("\t");
			val map = Map(
				"id" -> toJson("log-" + (list.size + 1)),
				"cell" -> toJson(normalize(cols))
			);
			map :: list
		}.reverse;
		
		Map(
			"start" -> toJson(1),
			"total" -> toJson(1),
			"page" -> toJson(1),
			"records" -> toJson(rows.size),
			"rows" -> toJson(data)
		);
	}
	
	private def normalize(cols: Array[String]): Array[String] = {
		if (cols.length == 76) {
			//Brint total columns to second
			cols.slice(0, 1) ++ cols.slice(73, 76) ++ cols.slice(1, 73)
		} else {
			cols
		}
	}
}

