if (typeof(flect) == "undefined") flect = {};
if (typeof(flect.app) == "undefined") flect.app = {};
if (typeof(flect.app.loganalyzer) == "undefined") flect.app.loganalyzer = {};

flect.app.loganalyzer.CacheStatus = function() {
	var values = [
		{ "name" : "Unprocessed"},
		{ "name" : "Ready"},
		{ "name" : "Found"},
		{ "name" : "NotFound"},
		{ "name" : "Error"}
	];
	for (var i=0; i<values.length; i++) {
		var status = values[i];
		this[status.name] = status;
	}
	$.extend(this, {
		"fromName" : function(v) {
			for (var i=0; i<values.length; i++) {
				if (values[i].name == v) return values[i];
			}
			return null;
		}
	});
}

flect.app.loganalyzer.LogAnalyzer = function(name, timeOffset) {
	var cntGrid, timeGrid, calendar,
		CacheStatus = new flect.app.loganalyzer.CacheStatus(),
		cntGridLoaded = false, timeGridLoaded = false;
	init();
	
	function init() {
		//Calendar
		calendar = $('#calendar').fullCalendar({
			// put your options and callbacks here
			"theme" : true,
			"weekMode" : "liquid",
			"aspectRatio" : 3,
			"dayClick" : function(date) {
				status(date, this);
			}
		});
		
		//Grid
		var cntLabels = ["Name"],
			timeLabels = ["Name"],
			colModel = [{ "name" : "name", "width" : 200}];
		
		for (var i=0; i<25; i++) {
			var suffix = i == 0 ? "All" : i;
			colModel.push({
				"name" : "cnt" + suffix,
				"width" : 50,
				"formatter" : "integer",
				"formatoptions" : {
					"defaultValue" : ""
				},
				"align" : "right"
			});
			colModel.push({
				"name" : "mm" + suffix,
				"width" : 50,
				"formatter" : "integer",
				"formatoptions" : {
					"defaultValue" : ""
				},
				"align" : "right"
			});
			colModel.push({
				"name" : "ms" + suffix,
				"width" : 50,
				"formatter" : "integer",
				"formatoptions" : {
					"defaultValue" : ""
				},
				"align" : "right"
			});
			
			cntLabels.push("All");
			cntLabels.push("Min");
			cntLabels.push("Sec");
			
			timeLabels.push("Cnt");
			timeLabels.push("Max");
			timeLabels.push("Avg");
		}
		for (var i=0; i<4; i++) {
			colModel[i].frozen = true;
		}
		cntGrid = $("#cntGrid").jqGrid({
			"autowidth" : true,
			"url" : "/" + name + "/logcount",
			"datatype" : "json",
			"mtype" : "POST",
			"colNames" : cntLabels,
			"colModel" : colModel,
			"rowNum" : 1000,
			"rownumbers" : false,
			"gridview" : true,
			"caption" : "The number of logs",
			"shrinkToFit" : false,
			"height" : 200,
			"loadComplete" : function(data) {
				cntGridLoaded = true;
				afterLoad();
			},
			"onSelectRow" : function(rowid, status, e) {
				/*
				if (rowid && status) {
					var data = $("#cellname-grid").getRowData(rowid);
					cellHighlight(data.name);
				}
				*/
			}
		});
		timeGrid = $("#timeGrid").jqGrid({
			"autowidth" : true,
			"url" : "/" + name + "/responsetime",
			"datatype" : "json",
			"mtype" : "POST",
			"colNames" : cntLabels,
			"colModel" : colModel,
			"rowNum" : 1000,
			"rownumbers" : false,
			"gridview" : true,
			"caption" : "ResponseTime",
			"shrinkToFit" : false,
			"height" : 200,
			"loadComplete" : function(data) {
				timeGridLoaded = true;
				afterLoad();
			},
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
			var suffix = i == 0 ? "All" : i;
			var title = i + timeOffset - 1;
			if (i == 0) {
				title = "Total";
			} else if (title < 0) {
				title = (title + 24) + ":00";
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
		}).jqGrid('setFrozenColumns');
		timeGrid.jqGrid("setGroupHeaders", {
			"useColSpanStyle" : true, 
			"groupHeaders" : groupHeaders
		}).jqGrid('setFrozenColumns');
	}
	function highlight(td) {
		calendar.find(".fc-state-highlight").removeClass("fc-state-highlight");
		$(td).addClass("fc-state-highlight")
	}
	function status(date, td) {
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
//console.log("test: " + data);
				var cs = CacheStatus.fromName(data);
				switch (cs) {
					case CacheStatus.Ready:
						$("#message").hide();
						loadData(year, month, day, td);
						break;
					case CacheStatus.NotFound:
						alert("ログがありません。");
						break;
					case CacheStatus.Found:
						$("#message").show();
						setTimeout(function() {
							status(date, td)
						}, 1000);
						break;
					case CacheStatus.Error:
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
	function loadData(year, month, day, td) {
		cntGridLoaded = false;
		timeGridLoaded = false;
		
		cntGrid.jqGrid("setGridParam", {
			"postData" : {
				"year" : year,
				"month" : month,
				"date" : day
			}
		}).trigger("reloadGrid");
		timeGrid.jqGrid("setGridParam", {
			"postData" : {
				"year" : year,
				"month" : month,
				"date" : day
			}
		}).trigger("reloadGrid");
		highlight(td);
	}
	function afterLoad() {
		if (cntGridLoaded && timeGridLoaded) {
			cntGridLoaded = false;
			timeGridLoaded = false;
//			setTimeout(drawMainChart, 0);
		}
	}
	function drawMainChart() {
		var cntData = cntGrid.jqGrid("getRowData", "log-2"),
			timeData = timeGrid.jqGrid("getRowData", "log-2");
		if (!cntData.name || !timeData.name) {
			return;
		}
		var data1 = [], data2 = [], barMax = 0;
		for (var i=1;i<=24; i++) {
			var v1 = cntData["cnt" + i];
			var v2 = timeData["cnt" + i];
			data1.push([i - 1, v1]);
			data2.push([i - 1, v2]);
			if (barMax < v1) {
				barMax = v1;
			}
		}
		Flotr.draw(document.getElementById("mainChart"), [data1], {
			"bars" : {
				"show" : true,
				"horizontal" : false,
				"shadowSize" : 0,
				"barWidth" : 0.8
			},
			"mouse" : {
				"track" : true,
				"relative" : true
			},
			"yaxis" : {
				"min" : 0,
				"autoscaleMargin" : 1
			}
		});
	}
}
