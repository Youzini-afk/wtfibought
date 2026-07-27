import { fmtDateTime, fmtTime } from '../../lib/utils';
import { useEffect, useRef } from 'react';
import * as echarts from 'echarts';
import { useIsDark } from '../../hooks/useIsDark';
import { chartUi, rgba } from '../../lib/chartTheme';
import type { QuantSnapshotSeriesPoint, QuantDeepAnalysisView } from '../../types';

interface Props {
  points: QuantSnapshotSeriesPoint[];
  analyses: QuantDeepAnalysisView[];
  /** 用户选的时间窗（小时），决定轴左界——后端另垫了 24h 历史点供右移，不能拿首点当窗口起点 */
  windowHours: number;
  onSelectAnalysis?: (a: QuantDeepAnalysisView) => void;
}

const TRIGGER_CN: Record<string, string> = { schedule: '定频', sentinel: '哨兵插队', chat: '对话触发', manual: '手动' };

const HOUR = 3_600_000;

/** 窄屏阈值（容器实宽）：手机竖屏与 PC 分档，只有跨档才重建 option */
const NARROW_PX = 560;

// 系列色经 CVD 校验（validate_palette）：三腿暖→冷跨度足够，粉色研判点与三腿在红绿色盲下也可分
const C_ANALYSIS = '#ec4899';
const C_FRAGILITY = '#f59e0b';

/**
 * 三腿定义：预测线按各自腿长右移到"兑现时刻"再画，于是"现在"竖线右侧自然形成 6h/12h/24h 三级阶梯，
 * 那一段就是尚未到期、无从判分的未来。实际值同腿同色、空心异形，竖着看即可对账某次预测准不准。
 */
const LEGS = [
  {
    key: 'H6', hours: 6, color: '#F97316', lineType: 'solid', symbol: 'circle',
    sigma: (p: QuantSnapshotSeriesPoint) => p.h6SigmaBps,
    realized: (p: QuantSnapshotSeriesPoint) => p.realizedH6AbsBps,
  },
  {
    key: 'H12', hours: 12, color: '#EF4444', lineType: 'dashed', symbol: 'triangle',
    sigma: (p: QuantSnapshotSeriesPoint) => p.h12SigmaBps,
    realized: (p: QuantSnapshotSeriesPoint) => p.realizedH12AbsBps,
  },
  {
    key: 'H24', hours: 24, color: '#A855F7', lineType: 'dotted', symbol: 'rect',
    sigma: (p: QuantSnapshotSeriesPoint) => p.h24SigmaBps,
    realized: (p: QuantSnapshotSeriesPoint) => p.realizedH24AbsBps,
  },
];

/** 窄屏/宽屏两套尺寸：手机上一律收边距缩字号细线条，六个系列才不会糊成一团 */
function metrics(narrow: boolean) {
  return narrow
    ? { gridTop: 26, gridLeft: 34, gridRight: 8, mainH: '46%', subTop: '72%', subH: '19%',
        axisFs: 9, legendFs: 9, legendLeft: 28, legendItemW: 8, legendGap: 8,
        lineW: 1.6, dot: 5, pin: 20, ySplit: 3, tipFs: 10 }
    : { gridTop: 30, gridLeft: 48, gridRight: 12, mainH: '48%', subTop: '74%', subH: '18%',
        axisFs: 10, legendFs: 10, legendLeft: 44, legendItemW: 10, legendGap: 12,
        lineW: 2, dot: 7, pin: 26, ySplit: 5, tipFs: 11 };
}

/**
 * 快照时间线：三腿预测 vs 已验证 realized + 脆弱度副图 + 深研判点标记。
 * 时间轴口径=兑现时刻（预测点右移各自腿长），脆弱度是当下状态量故不移，天然止于"现在"竖线。
 * 研判点画成可点击 scatter（贴 H6 线高度），点击联动下方研判卡。
 */
export function VolTimeline({ points, analyses, windowHours, onSelectAnalysis }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const chartRef = useRef<echarts.ECharts | null>(null);
  const selectRef = useRef(onSelectAnalysis);
  // 当前档位 + 最新 option 构造器：ResizeObserver 跨档重建时要用到，但它拿不到闭包里的新数据
  const narrowRef = useRef<boolean | null>(null);
  const buildRef = useRef<((narrow: boolean) => echarts.EChartsCoreOption) | null>(null);
  const isDark = useIsDark();

  // 点击回调走 ref：实例只建一次，事件闭包不能把首帧的回调锁死
  useEffect(() => { selectRef.current = onSelectAnalysis; }, [onSelectAnalysis]);

  // 实例只建一次：数据每 60s 刷一遍，若跟着 dispose+init，用户关掉的图例腿、正看的 tooltip 全被冲掉
  useEffect(() => {
    if (!ref.current) return;
    const el = ref.current;
    const chart = echarts.init(el);
    chartRef.current = chart;

    chart.on('click', (params) => {
      const data = params.data as { analysis?: QuantDeepAnalysisView } | undefined;
      if (params.seriesName === '深研判' && data?.analysis) selectRef.current?.(data.analysis);
    });

    // ResizeObserver 盯容器实宽：比 window.resize 准，侧栏收合/旋屏都跟得上
    const ro = new ResizeObserver(() => {
      chart.resize();
      const w = el.clientWidth;
      if (!w || !buildRef.current) return;
      const next = w < NARROW_PX;
      // 只有跨窄/宽档才重建：resize 时无脑 setOption 既费性能又会重置图例选中态
      if (next === narrowRef.current) return;
      narrowRef.current = next;
      chart.setOption(buildRef.current(next), true);
    });
    ro.observe(el);
    return () => { ro.disconnect(); chart.dispose(); chartRef.current = null; };
  }, []);

  useEffect(() => {
    const chart = chartRef.current;
    if (!chart) return;
    const ui = chartUi(isDark);

    // 预测/实际同腿同 x（都右移该腿长度），保住"竖着比高低"的对账能力
    const legSeries = LEGS.map(leg => {
      const shift = leg.hours * HOUR;
      return {
        ...leg,
        forecast: points.map(p => [p.closeTime + shift, leg.sigma(p)] as [number, number | null]),
        realized: points.filter(p => leg.realized(p) != null)
          .map(p => [p.closeTime + shift, leg.realized(p)!] as [number, number]),
      };
    });

    // "现在"取最新快照时刻而非 Date.now()：数据落库有延迟，对齐数据末端才不会凭空多出一截空白
    const nowTs = points.length ? points[points.length - 1].closeTime : Date.now();
    const xMax = nowTs + 24 * HOUR;
    // 左界只认用户选的窗口：拿首点推算会在库存不足 24h 时把历史整段裁没
    const xMin = nowTs - windowHours * HOUR;

    // 研判点落在真实发生时刻（历史事实不右移）；y 取该刻 H6 线高度让 pin 贴线
    const h6Line = legSeries[0].forecast;
    const sigmaAt = (t: number) => {
      let best: number | null = null;
      for (const [x, y] of h6Line) {
        if (x <= t) { if (y != null) best = y; } else break;
      }
      // 落在 H6 线起点之前（垫底数据不够时会有）就贴第一个有效值，别让 pin 掉到 y=0 底部
      return best ?? h6Line.find(([, y]) => y != null)?.[1] ?? 0;
    };
    const analysisDots = analyses.map(a => ({
      value: [a.closeTime, sigmaAt(a.closeTime)] as [number, number],
      analysis: a,
    }));

    const buildOption = (narrow: boolean) => {
      const m = metrics(narrow);
      return {
        // 上下双 grid：vol 主图 + 脆弱度副图，时间轴联动
        grid: [
          { top: m.gridTop, right: m.gridRight, left: m.gridLeft, height: m.mainH },
          { top: m.subTop, right: m.gridRight, left: m.gridLeft, height: m.subH },
        ],
        legend: {
          top: 2,
          left: m.legendLeft,
          itemWidth: m.legendItemW,
          itemHeight: 8,
          itemGap: m.legendGap,
          icon: 'roundRect',
          textStyle: { fontSize: m.legendFs, color: ui.axisLabel },
          // 每腿的线与散点同名，图例一项同时开关"预测+实际"，避免 8 项挤爆顶栏
          data: ['H6', 'H12', 'H24', '深研判', '脆弱度'],
        },
        axisPointer: { link: [{ xAxisIndex: 'all' }], lineStyle: { color: ui.gridLine } },
        tooltip: {
          trigger: 'axis',
          confine: true,
          ...ui.tooltip,
          textStyle: { ...ui.tooltip.textStyle, fontSize: m.tipFs },
          formatter: (params: unknown) => {
            const list = params as { seriesName: string; seriesType: string; value: [number, number | null]; marker: string }[];
            const valid = list.filter(p => p.value?.[1] != null);
            if (!valid.length) return '';
            const t = valid[0].value[0];
            // 脆弱度在副图、x 未右移，独立触发时头部不能写"兑现"
            const onlyFragility = valid.every(p => p.seriesName === '脆弱度');
            const rows = valid.map(p => {
              const v = p.value[1] as number;
              const leg = LEGS.find(l => l.key === p.seriesName);
              if (!leg) return `${p.marker}${p.seriesName}: ${v.toFixed(0)}`;
              // 同一 x 上各腿的发出时刻不同（H6 是 6h 前、H24 是 24h 前），逐行标出免得再生歧义
              const at = fmtTime(t - leg.hours * HOUR);
              const issued = `<span style="opacity:.55"> ${narrow ? `${at}发` : `${at} 发出`}</span>`;
              return p.seriesType === 'line'
                ? `${p.marker}${leg.key} 预测: ${v.toFixed(0)} bps${issued}`
                : `${p.marker}${leg.key} 实际: ${v.toFixed(0)} bps`;
            });
            return [`${onlyFragility ? '' : '兑现 '}${fmtDateTime(t)}`, ...rows].join('<br/>');
          },
        },
        xAxis: [
          {
            type: 'time', gridIndex: 0, min: xMin, max: xMax,
            axisLabel: { show: false }, axisLine: { lineStyle: { color: ui.gridLine } }, axisTick: { show: false },
          },
          {
            type: 'time', gridIndex: 1, min: xMin, max: xMax,
            // hideOverlap：窄屏时间标签宁可少画几个也不叠字
            axisLabel: { fontSize: m.axisFs, color: ui.axisLabel, hideOverlap: true, margin: narrow ? 6 : 8 },
            axisLine: { lineStyle: { color: ui.gridLine } }, axisTick: { show: false },
          },
        ],
        yAxis: [
          {
            // 轴名删掉：顶部与 legend 重叠，单位(bps)已在图下方指标说明里交代
            type: 'value', gridIndex: 0, splitNumber: m.ySplit,
            axisLabel: { fontSize: m.axisFs, color: ui.axisLabel },
            splitLine: { lineStyle: { color: ui.gridLine, opacity: 0.6 } },
          },
          {
            type: 'value', gridIndex: 1, max: 100, min: 0, splitNumber: 2,
            axisLabel: { fontSize: m.axisFs - 1, color: ui.axisLabel }, splitLine: { show: false },
          },
        ],
        series: [
          {
            // 分界参考层：空数据系列专门扛"现在"竖线+未来区底纹。挂到任何一条腿上，
            // 用户在图例里关掉那条腿就会连分界一起消失；名字不进 legend.data 即不受图例控制
            // 占位点值为 null：不入 y 轴取值也不进 tooltip，只为让这层稳定参与渲染
            name: '__nowRef', type: 'line', xAxisIndex: 0, yAxisIndex: 0, data: [[nowTs, null]],
            silent: true, legendHoverLink: false, tooltip: { show: false },
            markArea: {
              silent: true,
              // 窄屏底纹提一档：小屏上太淡就看不出"这片是未来"
              itemStyle: { color: rgba('#94a3b8', isDark ? (narrow ? 0.1 : 0.07) : (narrow ? 0.07 : 0.05)) },
              data: [[{ xAxis: nowTs }, { xAxis: xMax }]],
            },
            markLine: {
              silent: true, symbol: 'none',
              label: { formatter: '现在', position: 'insideEndTop', fontSize: m.axisFs, color: ui.axisLabel },
              lineStyle: { color: ui.axisLabel, type: 'dashed', width: 1, opacity: 0.7 },
              data: [{ xAxis: nowTs }],
            },
          },
          // 倒序铺开：H24 垫底、H6 压顶，主角腿不被长腿盖住
          ...[...legSeries].reverse().flatMap(leg => [
            {
              name: leg.key, type: 'line', xAxisIndex: 0, yAxisIndex: 0,
              data: leg.forecast, symbol: 'none', smooth: true, connectNulls: true,
              // 7d 窗口下三腿合计上万点，LTTB 采样保形状不糊线
              sampling: 'lttb',
              lineStyle: {
                width: m.lineW, type: leg.lineType, color: leg.color,
                // 窄屏关阴影：小屏上三条线的辉光叠在一起反而脏
                ...(narrow ? {} : { shadowColor: rgba(leg.color, 0.3), shadowBlur: 5, shadowOffsetY: 3 }),
              },
              itemStyle: { color: leg.color },
            },
            {
              name: leg.key, type: 'scatter', xAxisIndex: 0, yAxisIndex: 0,
              data: leg.realized, symbol: leg.symbol, symbolSize: m.dot,
              // 空心（面色填充+同色描边）：与实心预测线区分"实际/预测"，重叠也不糊成一坨
              itemStyle: { color: ui.card, borderColor: leg.color, borderWidth: narrow ? 1.2 : 1.4 },
            },
          ]),
          {
            name: '深研判', type: 'scatter', xAxisIndex: 0, yAxisIndex: 0,
            data: analysisDots, symbol: 'pin', symbolSize: m.pin,
            itemStyle: { color: C_ANALYSIS, shadowColor: rgba(C_ANALYSIS, 0.4), shadowBlur: 6 },
            tooltip: {
              formatter: (p: unknown) => {
                const a = (p as { data: { analysis: QuantDeepAnalysisView } }).data.analysis;
                return `${fmtDateTime(a.closeTime)}<br/>深研判 · ${TRIGGER_CN[a.triggerSource] || a.triggerSource}<br/><span style="opacity:.7">点击查看详情</span>`;
              },
            },
            z: 10,
          },
          {
            // 脆弱度=此刻市场结构状态，不是预测，故 x 不右移，线天然止于"现在"竖线
            name: '脆弱度', type: 'line', xAxisIndex: 1, yAxisIndex: 1,
            data: points.map(p => [p.closeTime, p.fragilityScore] as [number, number | null]),
            symbol: 'none', smooth: true, connectNulls: true, sampling: 'lttb',
            lineStyle: { width: narrow ? 1.2 : 1.5, color: C_FRAGILITY },
            itemStyle: { color: C_FRAGILITY },
            areaStyle: {
              color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: rgba(C_FRAGILITY, 0.22) },
                { offset: 1, color: rgba(C_FRAGILITY, 0.02) },
              ]),
            },
          },
        ],
      };
    };

    buildRef.current = buildOption;
    // 首帧容器可能还没量出宽度（此时按宽屏铺），ResizeObserver 首次回调会立刻纠正
    const w = ref.current?.clientWidth ?? 0;
    const narrow = narrowRef.current ?? (w > 0 && w < NARROW_PX);
    narrowRef.current = narrow;
    // 走 merge 不走 notMerge：60s 刷新只换数据，图例选中态和交互状态原样保留
    chart.setOption(buildOption(narrow));
  }, [points, analyses, windowHours, isDark]);

  // 手机压到 360：双 grid 再矮就读不出脆弱度副图了
  return <div ref={ref} className="w-full h-[360px] sm:h-[420px]" />;
}
