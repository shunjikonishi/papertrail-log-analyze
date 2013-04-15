package models;

import play.api.libs.json.Json.toJson;
import play.api.libs.json.JsValue;

object JqGrid {
	
	case class GridSort(sidx: String, sord: String) {
		def sortCol = {
			sidx match {
				case ""       => 0;
				case "name"   => 0;
				case "cntAll" => 1;
				case "mmAll"  => 2;
				case "msAll"  => 3;
				case x if (x.startsWith("cnt")) => Integer.parseInt(x.substring(3)) * 3 + 1; 
				case x if (x.startsWith("mm"))  => Integer.parseInt(x.substring(2)) * 3 + 2; 
				case x if (x.startsWith("ms"))  => Integer.parseInt(x.substring(2)) * 3 + 3; 
				case _ => 0;
			}
		}
		
		def sort(data: Array[String]) = {
			val col = sortCol;
			if (col == 0) {
				if (sord == "asc") data else data.reverse;
			} else {
				val ret = data.sortWith{ (row1, row2) =>
					val v1 = row1.split("\t").lift(col);
					val v2 = row2.split("\t").lift(col);
					(v1, v2) match {
						case (Some(s1), Some(s2)) => 
							Integer.parseInt(s1) < Integer.parseInt(s2);
						case (Some(s1), None) => true;
						case (None, Some(s2)) => false;
						case (None, None) => 
							row1 < row2;
					}
				};
				if (sord == "asc") ret else ret.reverse;
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
		val rows = csv.split("\n");
		val data = rows.foldLeft[List[Map[String, JsValue]]](List.empty) { (list, row) =>
			val cols = sort.sort(row.split("\t"));
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

