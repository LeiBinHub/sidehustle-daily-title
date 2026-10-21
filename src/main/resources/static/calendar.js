(function () {
  const WEEK = ["一", "二", "三", "四", "五", "六", "日"];

  function parse(value) {
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec((value || "").trim());
    if (!match) return null;
    const year = Number(match[1]);
    const month = Number(match[2]);
    const day = Number(match[3]);
    const date = new Date(year, month - 1, day);
    if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) return null;
    return date;
  }

  function iso(date) {
    const month = String(date.getMonth() + 1).padStart(2, "0");
    const day = String(date.getDate()).padStart(2, "0");
    return date.getFullYear() + "-" + month + "-" + day;
  }

  function submitFrom(input) {
    const form = input.form;
    if (!form) return;
    if (form.elements.page) form.elements.page.value = "1";
    if (form.requestSubmit) form.requestSubmit();
    else form.submit();
  }

  function mount(input) {
    const pop = document.createElement("div");
    pop.className = "cal";
    pop.hidden = true;
    input.parentElement.appendChild(pop);
    let cursor = parse(input.value) || new Date();

    function close() { pop.hidden = true; }

    function commit(date) {
      input.value = date ? iso(date) : "";
      close();
      submitFrom(input);
    }

    function render() {
      const year = cursor.getFullYear();
      const month = cursor.getMonth();
      const selected = parse(input.value);
      const today = new Date();
      const first = new Date(year, month, 1);
      const offset = (first.getDay() + 6) % 7;
      const start = new Date(year, month, 1 - offset);
      const days = [];
      for (let i = 0; i < 42; i++) {
        const date = new Date(start.getFullYear(), start.getMonth(), start.getDate() + i);
        const classes = ["cal-day"];
        if (date.getMonth() !== month) classes.push("out");
        if (selected && iso(date) === iso(selected)) classes.push("on");
        if (iso(date) === iso(today)) classes.push("today");
        days.push('<button type="button" class="' + classes.join(" ") + '" data-iso="' + iso(date) + '">' + date.getDate() + "</button>");
      }
      pop.innerHTML =
        '<div class="cal-head"><button type="button" data-nav="-1" aria-label="上个月">‹</button><strong>' +
        year + "年" + (month + 1) + "月</strong><button type=\"button\" data-nav=\"1\" aria-label=\"下个月\">›</button></div>" +
        '<div class="cal-week">' + WEEK.map((day) => "<span>" + day + "</span>").join("") + "</div>" +
        '<div class="cal-grid">' + days.join("") + "</div>" +
        '<div class="cal-foot"><button type="button" data-today>今天</button><button type="button" data-clear>清除</button></div>';

      pop.querySelector('[data-nav="-1"]').addEventListener("click", () => {
        cursor = new Date(year, month - 1, 1);
        render();
      });
      pop.querySelector('[data-nav="1"]').addEventListener("click", () => {
        cursor = new Date(year, month + 1, 1);
        render();
      });
      pop.querySelector("[data-today]").addEventListener("click", () => commit(new Date()));
      pop.querySelector("[data-clear]").addEventListener("click", () => commit(null));
      pop.querySelectorAll(".cal-day").forEach((button) => {
        button.addEventListener("click", () => commit(parse(button.dataset.iso)));
      });
    }

    function open() {
      cursor = parse(input.value) || new Date();
      render();
      pop.hidden = false;
    }

    pop.addEventListener("mousedown", (event) => event.preventDefault());
    input.addEventListener("focus", open);
    input.addEventListener("click", open);
    input.addEventListener("change", () => {
      if (pop.hidden) submitFrom(input);
    });
    input.addEventListener("keydown", (event) => {
      if (event.key === "Escape") close();
    });
    document.addEventListener("click", (event) => {
      if (!input.parentElement.contains(event.target)) close();
    });
  }

  document.querySelectorAll("input.date").forEach(mount);
})();
