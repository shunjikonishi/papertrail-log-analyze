if (typeof(flect) == "undefined") flect = {};
if (typeof(flect.app) == "undefined") flect.app = {};
if (typeof(flect.app.loganalyzer) == "undefined") flect.app.loganalyzer = {};

flect.app.loganalyzer.LogAnalyzer = function(name) {
	function status(date) {
		var year = date.getFullYear();
		var month = date.getMonth() + 1;
		var day = date.getDate();
		$.ajax({
			"url" : "/" + name + "/status",
			"data" : {
				"year" : year,
				"month" : month,
				"date" : day
			},
			"type" : "POST",
			"success" : function(data) {
				switch (data) {
					case "ready":
						$("#message").hide();
						loadData(year, month, day);
						break;
					case "notFound":
						alert("ログがありません。");
						break;
					case "found":
						$("#message").show();
						setTimeout(function() {
							status(date)
						}, 1000);
						break;
					case "error":
						$("#message").hide();
						alert("S3 access error");
						break;
					default:
						alert("Unknown status: " + data);
						break;
				}
			}
		});
	}
	function loadData(year, month, day) {
		cntGrid.jqGrid("setGridParam", {
			"postData" : {
				"year" : year,
				"month" : month,
				"date" : day
			}
		}).trigger("reloadGrid");
	}
	
	$('#calendar').fullCalendar({
		// put your options and callbacks here
		"theme" : true,
		"weekMode" : "liquid",
		"aspectRatio" : 3,
		"dayClick" : function(date) {
			status(date);
		}
	});
	
	var labels = ["Name"];
	var cntModel = [
		{ "name" : "name", "width" : 200, "frozen" : true}
	]
	for (var i=0; i<25; i++) {
		var suffix = i == 24 ? "All" : i + 1;
		cntModel.push({
			"name" : "cnt" + suffix,
			"width" : 50,
			"align" : "right"
		});
		cntModel.push({
			"name" : "mm" + suffix,
			"width" : 50,
			"align" : "right"
		});
		cntModel.push({
			"name" : "ms" + suffix,
			"width" : 50,
			"align" : "right"
		});
		labels.push("Cnt");
		labels.push("MM/Max");
		labels.push("MS/Avg");
	}
	var cntGrid = $("#cntGrid").jqGrid({
		"autowidth" : true,
		"url" : "/" + name + "/logcount",
		"datatype" : "json",
		"mtype" : "POST",
		"colNames" : labels,
		"colModel" : cntModel,
		"rowNum" : 1000,
		"rownumbers" : false,
		"gridview" : true,
		"caption" : "Log count",
		"shrinkToFit" : false,
		"height" : 300,
		"onSelectRow" : function(rowid, status, e) {
			/*
			if (rowid && status) {
				var data = $("#cellname-grid").getRowData(rowid);
				cellHighlight(data.name);
			}
			*/
		}
	});
	var groupHeaders = [];
	for (var i=0; i<25; i++) {
		var suffix = i == 24 ? "All" : i + 1;
		var title = i + 9;
		if (i == 24) {
			title = "Total";
		} else if (title < 24) {
			title += ":00";
		} else {
			title = (title - 24) + ":00";
		}
		groupHeaders.push({
			"startColumnName" : "cnt" + suffix,
			"numberOfColumns" : 3,
			"titleText" : title
		});
	}
	cntGrid.jqGrid("setGroupHeaders", {
		"useColSpanStyle" : true, 
		"groupHeaders" : groupHeaders
	});
	cntGrid.jqGrid('setFrozenColumns');
}
