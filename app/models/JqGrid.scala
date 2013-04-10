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
		var idx = 0;
		val rows = csv.split("\n");
		val data = rows.map{ row =>
			val cols = row.split(",");
			idx += 1;
			Map(
				"id" -> toJson("log-" + idx),
				"cell" -> toJson(normalize(cols))
			);
		};
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
			cols.slice(0, 1) ++ cols.slice(73, 76) ++ cols.slice(1, 73)
		} else {
			cols
		}
	}
}

