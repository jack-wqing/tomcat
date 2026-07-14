(function() {
  var style = getComputedStyle(document.documentElement);
  var accent = style.getPropertyValue('--accent').trim();
  var accent2 = style.getPropertyValue('--accent2').trim();
  var accent3 = style.getPropertyValue('--accent3').trim();
  var ink = style.getPropertyValue('--ink').trim();
  var muted = style.getPropertyValue('--muted').trim();
  var rule = style.getPropertyValue('--rule').trim();
  var bg2 = style.getPropertyValue('--bg2').trim();
  var warn = style.getPropertyValue('--warn').trim();

  // --- Chart: Tuning Before/After Comparison ---
  var chartCompare = echarts.init(document.getElementById('chart-compare'), null, { renderer: 'svg' });
  chartCompare.setOption({
    animation: false,
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      appendToBody: true,
      backgroundColor: bg2,
      borderColor: rule,
      textStyle: { color: ink, fontFamily: 'WorkSans, sans-serif' }
    },
    legend: {
      data: ['调优前', '调优后'],
      top: 5,
      textStyle: { color: muted, fontFamily: 'WorkSans, sans-serif' },
      itemWidth: 14,
      itemHeight: 10
    },
    grid: {
      left: '12%',
      right: '8%',
      top: '18%',
      bottom: '12%'
    },
    xAxis: {
      type: 'category',
      data: ['QPS\n(吞吐量)', '平均响应\n时间(ms)', 'P99延迟\n(ms)', 'GC停顿\n(ms)', '错误率\n(%)'],
      axisLine: { lineStyle: { color: rule } },
      axisLabel: {
        color: muted,
        fontFamily: 'WorkSans, sans-serif',
        fontSize: 11,
        interval: 0
      },
      axisTick: { show: false }
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      splitLine: { lineStyle: { color: rule, type: 'dashed' } },
      axisLabel: { color: muted, fontFamily: 'WorkSans, sans-serif' }
    },
    series: [
      {
        name: '调优前',
        type: 'bar',
        data: [1200, 350, 2500, 800, 2.5],
        itemStyle: {
          color: accent2,
          borderRadius: [4, 4, 0, 0]
        },
        barGap: '15%',
        barCategoryGap: '40%'
      },
      {
        name: '调优后',
        type: 'bar',
        data: [3500, 120, 600, 150, 0.1],
        itemStyle: {
          color: accent3,
          borderRadius: [4, 4, 0, 0]
        },
        label: {
          show: true,
          position: 'top',
          color: accent3,
          fontFamily: 'JetBrainsMono, monospace',
          fontSize: 11,
          fontWeight: 600,
          formatter: function(params) {
            var before = [1200, 350, 2500, 800, 2.5][params.dataIndex];
            var improvement = ((before - params.value) / before * 100).toFixed(0);
            if (params.dataIndex === 0) {
              improvement = ((params.value - before) / before * 100).toFixed(0);
              return '+' + improvement + '%';
            }
            return '-' + improvement + '%';
          }
        }
      }
    ]
  });

  window.addEventListener('resize', function() {
    chartCompare.resize();
  });
})();
