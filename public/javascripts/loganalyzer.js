if (typeof(flect) == "undefined") flect = {};
if (typeof(flect.app) == "undefined") flect.app = {};
if (typeof(flect.app.loganalyzer) == "undefined") flect.app.loganalyzer = {};

(function ($) {
	var MSG = flect.app.loganalyzer.MSG;
	//Classes
	function Enum(values) {
		for (var i=0; i<values.length; i++) {
			var v = values[i];
			this[v.name] = v;
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
	function Chart(app, elementId) {
		var currentData = currentKind = null,
			lineCol = "ms";
		
		function lineName() {
			var suffix = lineCol.toUpperCase();
			return MSG[currentKind.name + suffix];
		}
		function lineColor() {
			var n = 0;
			if (currentKind == GridKind.Count) {
				n = lineCol == "mm" ? 0 : 1;
			} else {
				n = lineCol == "mm" ? 2 : 3;
			}
			return Flotr.defaultOptions.colors[n];
		}
		function axis2Name() {
			return currentKind == GridKind.Count ? "Count" : "Time(ms)";
		}
		function draw(kind, data) {
			if (!data || !data.cnt1) {
				return;
			}
			currentKind = kind;
			currentData = data;
			
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
				"label" : lineName(),
				"yaxis" : 2,
				"color" : lineColor(),
				"lines" : {
					"show" : true
				}
			},ticks = [];
			for (var i=1;i<=24; i++) {
				var v1 = parseInt(data["cnt" + i], 10);
				var v2 = parseInt(data[lineCol + i], 10);
				bar1.data.push([i - 1, v1]);
				line1.data.push([i - 1, v2]);
				ticks.push([i - 1, app.convertTime(i)]);
			}
			var title = data.name;
			if (title.length > 50) {
				title = title.substring(0, 50) + "...";
			}
			Flotr.draw(document.getElementById(elementId), [bar1, line1], {
				"title" : title,
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
					"title" : axis2Name(),
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
		function changeLine() {
			if (!currentData) {
				return;
			}
			lineCol = lineCol == "ms" ? "mm" : "ms";
			draw(currentKind, currentData);
		}
		$.extend(this, {
			"draw" : draw,
			"changeLine" : changeLine
		});
	}
	
	function Calendar(app, div) {
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
	function Grid(app, kind, table) {
		var colDef = {
			"width" : 50,
			"formatter" : "integer",
			"formatoptions" : {
				"defaultValue" : ""
			},
			"align" : "right"
		},
			currentDate, cuurentSort, sortAsc, 
			drawOnLoad = false,
			labels = [MSG.name],
			colModel = [{ "name" : "name", "width" : 200}];
		for (var i=0; i<25; i++) {
			var suffix = i == 0 ? "All" : i;
			colModel.push($.extend({
				"name" : "cnt" + suffix
			}, colDef));
			colModel.push($.extend({
				"name" : "mm" + suffix
			}, colDef));
			colModel.push($.extend({
				"name" : "ms" + suffix
			}, colDef));
			
			labels.push(MSG[kind.name + "CNT"]);
			labels.push(MSG[kind.name + "MM"]);
			labels.push(MSG[kind.name + "MS"]);
		}
		for (var i=0; i<4; i++) {
			colModel[i].frozen = true;
		}
		var grid = table.jqGrid({
			"autowidth" : true,
			"url" : "/" + app.name + kind.path,
			"datatype" : "json",
			"mtype" : "POST",
			"colNames" : labels,
			"colModel" : colModel,
			"rowNum" : 1000,
			"rownumbers" : false,
			"gridview" : true,
			"caption" : MSG[kind.name + "Grid"],
			"shrinkToFit" : false,
			"height" : 200,
			"loadComplete" : function(data) {
				for (var i=0; i<data.rows.length; i++) {
					var cells = data.rows[i].cell;
					if (cells.length == 76) {
						var cnt = 0, mm = 0, ms = 0, cntIdx, mmIdx, msIdx,
							rowid = data.rows[i].id;
						for (var j=4; j<76; j+=3) {
							var vc = parseInt(cells[j], 10);
							var vm  = parseInt(cells[j+1], 10);
							var vs  = parseInt(cells[j+2], 10);
							var time = (j - 1) / 3;
							
							if (vc > cnt) {cnt = vc; cntIdx = time}
							if (vm > mm) {mm = vm; mmIdx = time}
							if (vs > ms) {ms = vs; msIdx = time}
						}
						grid.jqGrid("setCell", rowid, "cnt" + cntIdx, "", "max-cnt");
						grid.jqGrid("setCell", rowid, "mm" + mmIdx, "", "max-mm");
						grid.jqGrid("setCell", rowid, "ms" + msIdx, "", "max-ms");
					}
				}
				if (drawOnLoad && kind.drawOnLoad) {
					setTimeout(function() {
						var data = grid.jqGrid("getRowData", kind.drawOnLoad);
						if (data && data.name) {
							app.drawChart(kind, data);
						}
					}, 0);
					drawOnLoad = false;
				}
			},
			"onSelectRow" : function(rowid, status, e) {
				if (rowid) {
					var data = grid.getRowData(rowid);
					app.drawChart(kind, data);
				}
			},
			"onSortCol" : function(index, iCol, order) {
				var col = iCol;
				//Bug of jqGrid, when using frozen columns
				switch (index) {
					case "name"   : col =0; break;
					case "cntAll" : col =1; break;
					case "mmAll"  : col =2; break;
					case "msAll"  : col =3; break;
				}
				var asc = col == 0;
				if (col == currentSort) {
					asc = !sortAsc;
				}
				setTimeout(function() {
					sort(col, asc);
				}, 0);
				return "stop";
			}
		}).jqGrid('gridResize', { "minWidth" : 400, "minHeight" : 100});
		var groupHeaders = [];
		for (var i=0; i<25; i++) {
			var suffix = i == 0 ? "All" : i;
			var title = i == 0 ? MSG.total : app.convertTime(i);
			groupHeaders.push({
				"startColumnName" : "cnt" + suffix,
				"numberOfColumns" : 3,
				"titleText" : title
			});
		}
		grid.jqGrid("setGroupHeaders", {
			"useColSpanStyle" : true, 
			"groupHeaders" : groupHeaders
		}).jqGrid('setFrozenColumns');
		
		function reload(str) {
			currentDate = str;
			drawOnLoad = true;
			sort(0, true);
		}
		function sort(col, asc) {
			currentSort = col;
			sortAsc = asc;
			grid.jqGrid("setGridParam", {
				"postData" : {
					"date" : currentDate,
					"col" : col,
					"asc" : asc
				}
			}).trigger("reloadGrid");
		}
		$.extend(this, {
			"reload" : reload
		});
	}
	function SettingDialog(app, div) {
		function show(pass) {
			$.ajax({
				"url" : "/" + app.name + "/setting", 
				"type" : "POST",
				"data" : {
					"passphrase" : pass
				},
				"success" : function(data) {
					div.empty().append(data);
					$("#settingTabs").tabs();
					div.dialog({
						"title" : MSG.setting,
						"buttons" : [
							{
								"text" : MSG.ok,
								"click" : function() {
									updateSetting(pass);
								}
							},
							{
								"text" : MSG.cancel,
								"click" : function() { 
									close();
								}
							}
						],
						"width" : "600px",
						"modal" : true
					});
				},
				"error" : function() {
					//ignore
				}
			});
		}
		function close() {
			div.dialog("close").dialog("destroy").empty();
		}
		function buildSetting() {
			var setting = {
				"counters" : [],
				"options" : {
				}
			};
			div.find(":input").each(function() {
				var el = $(this);
				var name = el.attr("name");
				var value = el.val();
				if (name == "counters") {
					if (el.is(":checked")) {
						setting.counters.push(value);
					}
				} else {
					var opName = name.split(".");
					var counterName = opName[0],
						propName = opName[1];
					if (!setting.options[counterName]) {
						setting.options[counterName] = {};
					}
					var op = setting.options[counterName];
					if (el.is(":checkbox")) {
						op[propName] = el.is(":checked");
					} else if (el.is(".number")) {
						var n = parseInt(value, 10);
						if (!n) {
							throw "Invalid number: " + value;
						}
						op[propName] = n;
					} else if (el.is("textarea")) {
						if (value) {
							var rows = value.split("\n");
							for (var i=0; i<rows.length; i++) {
								//Error check
								new RegExp(rows[i]);
							}
							op[propName] = rows;
						}
					} else {
						throw "Unknown parameter " + name;
					}
				}
			});
			return setting;
		}
		function updateSetting(pass) {
			try {
				var json = JSON.stringify(buildSetting());
				$.ajax({
					"url" : "/" + app.name + "/updateSetting",
					"data" : {
						"json" : json,
						"passphrase" : pass
					},
					"type" : "POST",
					"success" : function(data) {
						if (data == "OK") {
							close();
						} else {
							alert(data);
						}
					}
				});
			} catch (e) {
				alert(e);
			}
		}
		$.extend(this, {
			"show" : show
		});
	}
	function PassphraseDialog(app, div) {
		var passInput = div.find(":password");
		
		function doCheck(nextFunc) {
			div.dialog("close");
			var value = passInput.val();
			$.ajax({
				"url" : "/" + app.name + "/passphrase",
				"type" : "POST",
				"data" : {
					"passphrase" : value
				},
				"success" : function(data) {
					if (data == "OK") {
						nextFunc(value);
					} else {
						alert(MSG.invalidPassphrase);
					}
				}
			});
		}
		function show(nextFunc) {
			passInput.val("");
			div.dialog({
				"title" : MSG.inputPassphrase,
				"buttons" : [
					{
						"text" : MSG.ok,
						"click" : function() {
							doCheck(nextFunc);
						}
					},
					{
						"text" : MSG.cancel,
						"click" : function() { 
							div.dialog("close");
						}
					}
				],
				"modal" : true
			});
		}
		$.extend(this, {
			"show" : show
		});
	}
	function LoadingMessage(app, div) {
		$.extend(this, {
			"show" : function() { div.show();},
			"hide" : function() { div.hide();}
		});
	}
	//Enums
	var CacheStatus = new Enum([
		{ "name" : "Unprocessed"},
		{ "name" : "Ready"},
		{ "name" : "Found"},
		{ "name" : "NotFound"},
		{ "name" : "Error"}
	]);
	
	var GridKind = new Enum([
		{ "name" : "Count", "path" : "/logcount"},
		{ "name" : "Time",  "path" : "/responsetime", "drawOnLoad" : "log-2"},
	]);
	
	//Application
	flect.app.loganalyzer.LogAnalyzer = function(name, passRequired, timeOffset) {
		function downloadSummary() {
			var d = calendar.currentDate();
			if (d) {
				window.open("/" + name + "/show/" + calendar.formatDate(d));
			} else {
				alert(MSG.notDisplayedLog);
			}
		}
		function downloadRaw(pass) {
			var d = calendar.currentDate();
			if (d) {
				var form = $("#downloadForm");
				form.attr("action", "/" + name + "/download/" + calendar.formatDate(d));
				$("#downloadPassphrase").val(pass);
				form[0].submit();
			} else {
				alert(MSG.notDisplayedLog);
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
							loadingMessage.hide();
							loadData(date);
							break;
						case CacheStatus.NotFound:
							alert(MSG.notFoundLog);
							break;
						case CacheStatus.Found:
							loadingMessage.show();
							setTimeout(function() {
								status(date)
							}, 1000);
							break;
						case CacheStatus.Error:
							loadingMessage.hide();
							alert(MSG.s3error);
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
			cntGrid.reload(str);
			timeGrid.reload(str);
			calendar.currentDate(date);
		}
		function drawChart(kind, data) {
			chart.draw(kind, data);
			chartBtn.show();
		}
		$.extend(this, {
			"convertTime" : convertTime,
			"status" : status,
			"name" : name,
			"drawChart" : drawChart
		});
		var self = this,
			loadingMessage = new LoadingMessage(this, $("#message")),
			calendar = new Calendar(this, $('#calendar')),
			chart = new Chart(this, "mainChart"),
			cntGrid = new Grid(this, GridKind.Count, $("#cntGrid")), 
			timeGrid = new Grid(this, GridKind.Time, $("#timeGrid")),
			settingDialog = new SettingDialog(this, $("#settingDialog")),
			passDialog = new PassphraseDialog(this, $("#passDialog")),
			
			//Buttons
			dlSummaryBtn = $("#downloadSummary").click(downloadSummary),
			dlRawBtn = $("#downloadRaw").click(function() {
				var d = calendar.currentDate();
				if (!d) {
					alert(MSG.notDisplayedLog);
					return;
				}
				if (passRequired) {
					passDialog.show(downloadRaw);
				} else {
					downloadRaw("");
				}
			}),
			settingBtn = $("#setting").click(function() {
				if (passRequired) {
					passDialog.show(settingDialog.show);
				} else {
					settingDialog.show("");
				}
			}),
			chartBtn = $("#chartBtn").button().click(function() {
				chart.changeLine();
			});
	}
})(jQuery);
