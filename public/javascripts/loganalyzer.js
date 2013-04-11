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
		cntGridLoaded = false, timeGridLoaded = false,
		cntChartData, timeChartData;
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
				if (rowid && status) {
					var data = cntGrid.getRowData(rowid);
					if (data && data.cnt1) {
						cntChartData = data;
						drawChart();
					}
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
			"caption" : "ResponseTime",
			"shrinkToFit" : false,
			"height" : 200,
			"loadComplete" : function(data) {
				timeGridLoaded = true;
				afterLoad();
			},
			"onSelectRow" : function(rowid, status, e) {
				if (rowid && status) {
					var data = timeGrid.getRowData(rowid);
					if (data && data.cnt1) {
						cntChartData = data;
						timeChartData = data;
						drawChart();
					}
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
		
		$("#test").click(function() {
			window.open("/" + name + "/show/2013/4/9");
		});
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
		cntChartData = null;
		timeChartData = null;
		
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
			setTimeout(drawInitialChart, 0);
		}
	}
	function drawInitialChart() {
		cntChartData = cntGrid.jqGrid("getRowData", "log-2"),
		timeChartData = timeGrid.jqGrid("getRowData", "log-2");
		drawChart();
	}
	function drawChart() {
		if (!cntChartData || !cntChartData.name || !timeChartData || !timeChartData.name) {
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
			"label" : "Average",
			"yaxis" : 2,
			"lines" : {
				"show" : true
			}
		},ticks = [];
		for (var i=1;i<=24; i++) {
			var v1 = parseInt(cntChartData["cnt" + i], 10);
			var v2 = parseInt(timeChartData["ms" + i], 10);
			bar1.data.push([i - 1, v1]);
			line1.data.push([i - 1, v2]);
			ticks.push([i - 1, convertTime(i)]);
		}
		Flotr.draw(document.getElementById("mainChart"), [bar1, line1], {
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
}
