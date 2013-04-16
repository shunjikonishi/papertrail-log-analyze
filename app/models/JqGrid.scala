package models;

import play.api.libs.json.Json.toJson;
import play.api.libs.json.JsValue;

object JqGrid {
	
	case class GridSort(col: Int, asc: Boolean) {
		
		def sort(data: Array[String]) = {
			val idx = normalizeIndex(col);
			if (idx == 0) {
				if (asc) data else data.reverse;
			} else {
				val ret = data.sortWith{ (row1, row2) =>
					val v1 = row1.split("\t").lift(idx);
					val v2 = row2.split("\t").lift(idx);
					(v1, v2) match {
						case (Some(s1), Some(s2)) => 
							Integer.parseInt(s1) < Integer.parseInt(s2);
						case (Some(s1), None) => 
							false;
						case (None, Some(s2)) => 
							true;
						case (None, None) => 
							row1 < row2;
					}
				};
				if (asc) ret else ret.reverse;
			}
		}
	}
	
	def empty = {
		Map(
			"start" -> toJson(1),
			"total" -> toJson(0),
			"page" -> toJson(1),
			"records" -> toJson(0),
			"rows" -> toJson(new Array[JsValue](0))
		);
	}
	
	def data(csv: String, sort: GridSort) = {
		val rows = sort.sort(csv.split("\n"));
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
	
	private def normalizeIndex(num: Int) = {
		num match {
			case 0 => 0;
			case n if n <= 3 => n + 72;
			case n if n <= 75 => n - 3;
			case _  => 0;
		}
	}
}

