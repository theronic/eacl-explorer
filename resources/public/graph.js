(function () {
  function ensureD3() {
    if (window.d3) {
      return Promise.resolve(window.d3);
    }

    return new Promise(function (resolve, reject) {
      var script = document.createElement("script");
      script.src = "https://cdn.jsdelivr.net/npm/d3@7/dist/d3.min.js";
      script.onload = function () {
        resolve(window.d3);
      };
      script.onerror = reject;
      document.head.appendChild(script);
    });
  }

  function typeColor(type) {
    switch (type) {
      case "account":
        return "#d88d16";
      case "team":
        return "#d0552b";
      case "vpc":
        return "#0d7db8";
      case "server":
        return "#1c8f4c";
      case "user":
        return "#8a4ddb";
      case "platform":
        return "#7f4f24";
      default:
        return "#5f665d";
    }
  }

  function nodeRadius(node) {
    return node.kind === "permission" ? 18 : 24;
  }

  function collisionRadius(node) {
    return node.kind === "permission" ? 46 : 58;
  }

  function linkDistance(link) {
    switch (link.kind) {
      case "defines":
        return 62;
      case "permission":
        return 92;
      default:
        return 138;
    }
  }

  function render(containerId, graphData) {
    var container = document.getElementById(containerId);
    if (!container) {
      return;
    }

    ensureD3().then(function (d3) {
      container.innerHTML = "";

      var width = Math.max(container.clientWidth, 360);
      var height = Math.max(container.clientHeight || 0, 500);
      var svg = d3.select(container).append("svg")
        .attr("width", width)
        .attr("height", height)
        .attr("viewBox", "0 0 " + width + " " + height);

      var defs = svg.append("defs");

      defs.append("marker")
        .attr("id", "eacl-arrowhead")
        .attr("viewBox", "0 -5 10 10")
        .attr("refX", 24)
        .attr("refY", 0)
        .attr("markerWidth", 7)
        .attr("markerHeight", 7)
        .attr("orient", "auto")
        .append("path")
        .attr("d", "M0,-5L10,0L0,5")
        .attr("fill", "#958b78");

      defs.append("marker")
        .attr("id", "eacl-arrowhead-soft")
        .attr("viewBox", "0 -5 10 10")
        .attr("refX", 18)
        .attr("refY", 0)
        .attr("markerWidth", 6)
        .attr("markerHeight", 6)
        .attr("orient", "auto")
        .append("path")
        .attr("d", "M0,-5L10,0L0,5")
        .attr("fill", "#0d8f73");

      var nodes = (graphData.nodes || []).map(function (node) {
        return Object.assign({x: width / 2, y: height / 2}, node);
      });
      var links = (graphData.links || []).map(function (link) {
        return Object.assign({}, link);
      });

      var simulation = d3.forceSimulation(nodes)
        .force("link", d3.forceLink(links)
          .id(function (node) {
            return node.id;
          })
          .distance(linkDistance)
          .strength(function (link) {
            return link.kind === "defines" ? 0.7 : 0.35;
          }))
        .force("charge", d3.forceManyBody().strength(-430))
        .force("center", d3.forceCenter(width / 2, height / 2))
        .force("collision", d3.forceCollide().radius(collisionRadius));

      var link = svg.append("g")
        .attr("stroke-linecap", "round")
        .selectAll("line")
        .data(links)
        .join("line")
        .attr("stroke", function (item) {
          if (item.kind === "defines") {
            return "#c8b79b";
          }
          return item.kind === "permission" ? "#0d8f73" : "#958b78";
        })
        .attr("stroke-width", function (item) {
          return item.kind === "relation" ? 1.5 : 1.3;
        })
        .attr("stroke-dasharray", function (item) {
          return item.kind === "relation" ? null : "6 5";
        })
        .attr("marker-end", function (item) {
          return item.kind === "relation" ? "url(#eacl-arrowhead)" : "url(#eacl-arrowhead-soft)";
        });

      var linkLabel = svg.append("g")
        .selectAll("text")
        .data(links.filter(function (item) {
          return item.kind !== "defines";
        }))
        .join("text")
        .attr("font-family", "'IBM Plex Mono', monospace")
        .attr("font-size", 10)
        .attr("fill", "#5f665d")
        .attr("text-anchor", "middle")
        .text(function (item) {
          return item.label;
        });

      var drag = d3.drag()
        .on("start", function (event, node) {
          if (!event.active) {
            simulation.alphaTarget(0.32).restart();
          }
          node.fx = node.x;
          node.fy = node.y;
        })
        .on("drag", function (event, node) {
          node.fx = event.x;
          node.fy = event.y;
        })
        .on("end", function (event, node) {
          if (!event.active) {
            simulation.alphaTarget(0);
          }
          node.fx = null;
          node.fy = null;
        });

      var node = svg.append("g")
        .selectAll("g")
        .data(nodes)
        .join("g")
        .call(drag);

      var resourceNodes = node.filter(function (item) {
        return item.kind !== "permission";
      });

      resourceNodes.append("circle")
        .attr("r", function (item) {
          return nodeRadius(item);
        })
        .attr("fill", function (item) {
          return typeColor(item.type || item.id);
        })
        .attr("opacity", 0.9)
        .attr("stroke", "#fffaf0")
        .attr("stroke-width", 2.5);

      resourceNodes.append("text")
        .attr("text-anchor", "middle")
        .attr("dy", "0.35em")
        .attr("font-family", "'Space Grotesk', sans-serif")
        .attr("font-size", 10)
        .attr("font-weight", 700)
        .attr("fill", "#182126")
        .text(function (item) {
          return item.label || item.id;
        });

      var permissionNodes = node.filter(function (item) {
        return item.kind === "permission";
      });

      permissionNodes.append("rect")
        .attr("x", -42)
        .attr("y", -16)
        .attr("rx", 13)
        .attr("ry", 13)
        .attr("width", 84)
        .attr("height", 32)
        .attr("fill", "#f9f4ea")
        .attr("stroke", function (item) {
          return typeColor(item.type);
        })
        .attr("stroke-width", 2);

      permissionNodes.append("text")
        .attr("text-anchor", "middle")
        .attr("dy", "-0.1em")
        .attr("font-family", "'Space Grotesk', sans-serif")
        .attr("font-size", 10)
        .attr("font-weight", 700)
        .attr("fill", "#182126")
        .text(function (item) {
          return item.label || item.id;
        });

      permissionNodes.append("text")
        .attr("text-anchor", "middle")
        .attr("dy", "1.05em")
        .attr("font-family", "'IBM Plex Mono', monospace")
        .attr("font-size", 8.5)
        .attr("fill", "#5f665d")
        .text(function (item) {
          return item.resourceType;
        });

      node.append("title")
        .text(function (item) {
          return item.kind === "permission"
            ? item.resourceType + ":" + item.label
            : (item.label || item.id);
        });

      simulation.on("tick", function () {
        link
          .attr("x1", function (item) { return item.source.x; })
          .attr("y1", function (item) { return item.source.y; })
          .attr("x2", function (item) { return item.target.x; })
          .attr("y2", function (item) { return item.target.y; });

        linkLabel
          .attr("x", function (item) { return (item.source.x + item.target.x) / 2; })
          .attr("y", function (item) { return (item.source.y + item.target.y) / 2; });

        node.attr("transform", function (item) {
          return "translate(" + item.x + "," + item.y + ")";
        });
      });
    });
  }

  window.EaclSchemaGraph = {render: render};
}());
