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

flect.app.loganalyzer.Chart = function(app, elementId) {
	function lineName(kind) {
		return kind == "count" ? "Sec-Max" : "Average";
	}
	function draw(kind, data) {
		if (!data || !data.name) {
			return;
		}
		var bar1 = {
			"data" : [],
			"bars" : {
				"show" : true,
				"horizontal" : false,
				"shadowSize" : 0,
				"barWidth" : 0.8
			}
		}, line1 = {
			"data" : [],
			"label" : lineName(kind),
			"yaxis" : 2,
			"lines" : {
				"show" : true
			}
		},ticks = [];
		for (var i=1;i<=24; i++) {
			var v1 = parseInt(data["cnt" + i], 10);
			var v2 = parseInt(data["ms" + i], 10);
			bar1.data.push([i - 1, v1]);
			line1.data.push([i - 1, v2]);
			ticks.push([i - 1, app.convertTime(i)]);
		}
		Flotr.draw(document.getElementById(elementId), [bar1, line1], {
			"mouse" : {
				"track" : true,
				"trackFormatter" : function(obj) {
					return Math.ceil(obj.y);
				},
				"relative" : true
			},
			"xaxis" : {
				"ticks" : ticks,
				"labelsAngle" : 45
			},
			"yaxis" : {
				"min" : 0,
				"title" : "Count",
				"titleAngle" : 90,
				"tickFormatter" : tickFormatter,
				"autoscaleMargin" : 1,
			},
			"y2axis" : {
				"color" : "#FF0000",
				"min" : 0,
				"title" : "Time(ms)",
				"titleAngle" : -90,
				"tickFormatter" : tickFormatter,
				"autoscaleMargin" : 1
			},
			"HtmlText" : false
		});
		function tickFormatter(val, op) {
			return Math.ceil(val) + "";
		}
	}
	$.extend(this, {
		"draw" : draw
	});
}

flect.app.loganalyzer.Calendar = function(app, div) {
	var current = null,
		calendar = div.fullCalendar({
			// put your options and callbacks here
			"theme" : true,
			"weekMode" : "liquid",
			"aspectRatio" : 3,
			"dayClick" : function(date) {
				app.status(date);
			}
		});
	function formatDate(date) {
		var year = date.getFullYear();
		var month = date.getMonth() + 1;
		var day = date.getDate();
		if (month < 10) {
			month = "0" + month;
		}
		if (day < 10) {
			day = "0" + day;
		}
		return year + "-" + month + "-" + day;
	}
	function highlight(d) {
		calendar.find(".fc-state-highlight").removeClass("fc-state-highlight");
		calendar.find("td[data-date=" + formatDate(d) + "]").addClass("fc-state-highlight")
	}
	function currentDate(d) {
		if (d) {
			current = d;
			highlight(d);
			return this;
		} else {
			return current;
		}
	}
	$.extend(this, {
		"formatDate" : formatDate,
		"currentDate" : currentDate
	});
}

flect.app.loganalyzer.LogAnalyzer = function(name, timeOffset) {
	var self = this,
		cntGrid, timeGrid, calendar, chart,
		CacheStatus = new flect.app.loganalyzer.CacheStatus();
	init();
	
	function init() {
		//Calendar
		calendar = new flect.app.loganalyzer.Calendar(self, $('#calendar'));
		//Chart
		chart = new flect.app.loganalyzer.Chart(self, "mainChart");
		
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
			"caption" : "ログ件数と1分または1秒あたりの最大出力件数",
			"shrinkToFit" : false,
			"height" : 200,
			"onSelectRow" : function(rowid, status, e) {
				if (rowid && status) {
					var data = cntGrid.getRowData(rowid);
					chart.draw("count", data);
				}
			}
		});
		timeGrid = $("#timeGrid").jqGrid({
			"autowidth" : true,
			"url" : "/" + name + "/responsetime",
			"datatype" : "json",
			"mtype" : "POST",
			"colNames" : timeLabels,
			"colModel" : colModel,
			"rowNum" : 1000,
			"rownumbers" : false,
			"gridview" : true,
			"caption" : "ログ内の数値の件数、最大値、平均値",
			"shrinkToFit" : false,
			"height" : 200,
			"loadComplete" : function(data) {
				drawInitialChart();
			},
			"onSelectRow" : function(rowid, status, e) {
				if (rowid && status) {
					var data = timeGrid.getRowData(rowid);
					chart.draw("time", data);
				}
			}
		});
		var groupHeaders = [];
		for (var i=0; i<25; i++) {
			var suffix = i == 0 ? "All" : i;
			var title = i == 0 ? "Total" : convertTime(i);
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
		
		$("#test").click(download);
	}
	function download() {
		var d = calendar.currentDate();
		if (d) {
			window.open("/" + name + "/show/" + calendar.formatDate(d));
		} else {
			alert("ログが表示されていません");
		}
	}
	function convertTime(n) {
		var time = n + timeOffset - 1;
		if (time < 0) {
			time += 24;
		} else if (time >= 24) {
			time -= 24;
		}
		return time + ":00";
	}
	function status(date) {
		$.ajax({
			"url" : "/" + name + "/status",
			"data" : {
				"date" : calendar.formatDate(date)
			},
			"type" : "POST",
			"success" : function(data) {
				var cs = CacheStatus.fromName(data);
				switch (cs) {
					case CacheStatus.Ready:
						$("#message").hide();
						loadData(date);
						break;
					case CacheStatus.NotFound:
						alert("ログがありません。");
						break;
					case CacheStatus.Found:
						$("#message").show();
						setTimeout(function() {
							status(date)
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
	function loadData(date) {
		var str = calendar.formatDate(date);
		cntGrid.jqGrid("setGridParam", {
			"postData" : {
				"date" : str
			}
		}).trigger("reloadGrid");
		timeGrid.jqGrid("setGridParam", {
			"postData" : {
				"date" : str
			}
		}).trigger("reloadGrid");
		calendar.currentDate(date);
	}
	function drawInitialChart() {
		setTimeout(function() {
			var data = timeGrid.jqGrid("getRowData", "log-2");
			chart.draw("time", data);
		}, 0);
	}
	$.extend(this, {
		"convertTime" : convertTime,
		"status" : status
	});
}
