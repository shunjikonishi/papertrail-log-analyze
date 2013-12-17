if (typeof(flect) == "undefined") flect = {};
if (typeof(flect.app) == "undefined") flect.app = {};
if (typeof(flect.app.logmetrics) == "undefined") flect.app.logmetrics = {};

(function ($) {
	var options = {
		xaxis : {
			mode : 'time', 
			labelsAngle : 45
		},
		yaxis : {
			min : 0
		},
		selection : {
			mode : 'x'
		},
		legend : {
			position : 'se',
			backgroundColor : '#D2E8FF' 
		},
		HtmlText : false
	};
	function drawGraph(name, container, data, opts) {
		o = Flotr._.extend(Flotr._.clone(options), opts || {});
		o.title = name;
		return Flotr.draw(
			container,
			data,
			o
		);
	}
	
	//Application
	flect.app.logmetrics.LogMetrics = function(date, data) {
		function parseCsv(keys, lines, csv) {
			var rows = csv.split("\n");
			for (var i=0; i<rows.length; i++) {
				var cols = rows[i].split(",");
				if (cols.length != keys.length + 1) {
					continue;
				}
				var time = new Date(cols[0]).getTime(),
					idx = 0;
				for (var j=0; j<keys.length; j++) {
					if (!showLines[keys[j]]) {
						continue;
					}
					var num = cols[j+1];
					if (num.length > 0) {
						lines[idx].data.push([time, num]);
					}
					idx++;
				}
			}
		}
		function setProgramVisible(key, value) {
			showPgm[key] = value;
			draw();
		}
		function setLineVisible(key, value) {
			showLines[key] = value;
			draw();
		}
		function draw() {
			for (var name in data) {
				var lines = [],
					$container = $("#chart-" + name.replace(".", "-"));
				$container.css("display", showPgm[name] ? "" : "none");
				if (!showPgm[name]) {
					continue;
				}
				for (var i=0; i<keys.length; i++) {
					if (!showLines[keys[i]]) {
						continue;
					}
					lines.push({
						data: [],
						label: keys[i]
					});
				}
				parseCsv(keys, lines, data[name]);
				doDraw(name, $container[0], lines);
			}
		}
		function doDraw(name, container, lines) {
			var graph = drawGraph(name, container, lines);
			Flotr.EventAdapter.observe(container, 'flotr:select', function(area){
				graph = drawGraph(name, container, lines, {
					xaxis : { min : area.x1, max : area.x2, mode : 'time', labelsAngle : 45 },
					yaxis : { min : area.y1, max : area.y2 }
				});
			});
			Flotr.EventAdapter.observe(container, 'flotr:click', function () {
				graph = drawGraph(name, container, lines);
			});
		}
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
		var keys = data.keys,
			showLines = {},
			showPgm = {},
			$div = $("#data"),
			$program = $("#program"),
			$lines = $("#lines");
		delete data.keys;
		
		for (var i=0; i<keys.length; i++) {
			var key = keys[i],
				cb = $("<label><input type='checkbox' class='lines' value='" + key + "' checked='checked'/>" + key + "</label>");
			$lines.append(cb);
			showLines[key] = true;
		}
		for (var name in data) {
			showPgm[name] = true;
			var $container = $("<div/>"),
				cb = $("<label><input type='checkbox' class='program' value='" + name + "' checked='checked'/>" + name + "</label>");
			$program.append(cb);
			$container.attr("id", "chart-" + name.replace(".", "-"));
			$container.css({
				"height" : "400px",
				"width" : "1000px"
			});
			$div.append($container);
		}
		$(".lines").change(function() {
			var cb = $(this);
			showLines[cb.attr("value")] = cb.is(":checked");
			draw();
		});
		$(".program").change(function() {
			var cb = $(this);
			showPgm[cb.attr("value")] = cb.is(":checked");
			draw();
		});
		draw();
		
		$("#updateKey").click(function() {
			var href = location.href,
				key = $("#keywords").val(),
				idx = href.indexOf('?');
			if (idx != -1) {
				href = href.substring(0, idx);
			}
			location.href = href + "?key=" + encodeURI(key);
		});
		var calendar = $("#calendar").fullCalendar({
			// put your options and callbacks here
			"theme" : true,
			"weekMode" : "liquid",
			"aspectRatio" : 3,
			"dayClick" : function(date) {
				var d = formatDate(date),
					key = $("#keywords").val(),
					href = location.href,
					idx = href.lastIndexOf('/');
				href = href.substring(0, idx) + "/" + d + "?key=" + encodeURI(key);
				location.href = href;
			}
		});
		calendar.find(".fc-today").removeClass("ui-state-highlight");
		calendar.find("td[data-date=" + date + "]").addClass("ui-state-highlight");
		
	}
})(jQuery);
