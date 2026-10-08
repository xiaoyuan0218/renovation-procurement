<script setup>
import { helpVisible } from '../help'
</script>

<template>
  <el-dialog v-model="helpVisible" width="640px" top="6vh" append-to-body
             title="使用说明">
    <div class="help">
      <p class="lead">
        采知道是一套自己部署的装修采购账本。所有数据存在你这台服务器上，
        不经过任何第三方；手机上的单机版还能把数据同步过来。
      </p>

      <h4>几个词先说清楚</h4>
      <dl>
        <dt>清单</dt>
        <dd>
          一套独立的账。可以有好几份（装修一份、年货一份），互不影响，
          顶部左上角切换。
        </dd>
        <dt>分组</dt>
        <dd>房间或用途，比如客厅、主卧、全屋。用来回答「哪个房间还差什么」。</dd>
        <dt>分类</dt>
        <dd>物料的归类，比如灯具、五金、家电。用来在总览里看各类占比。</dd>
        <dt>物料</dt>
        <dd>
          要买的东西，记总量、单位、单价。单价有两个：原价和日常价
          （活动价、到手价），日常价留空就按原价算。
        </dd>
        <dt>分配（布点）</dt>
        <dd>
          这条物料在各个房间分别要几个。填了分配，总量就由分配合计决定；
          一次采购买的东西可能分属好几个房间，所以记账时能多选涉及的分组。
        </dd>
        <dt>采购记录</dt>
        <dd>
          一次实际的购买：买了几个、花了多少钱、哪天、哪家店、订单号。
          可以有很多笔（分批买、换店买）。
        </dd>
      </dl>

      <h4>三个金额口径，别搞混</h4>
      <ul>
        <li>原价小计 = 总量 × 原价</li>
        <li>日常价小计 = 总量 × 日常价</li>
        <li>已付 = 所有采购记录的金额之和</li>
      </ul>
      <p>
        额外费用（运费、安装费）是单独一笔账，不摊进任何物料的单价，
        所以不会出现在上面三个数里。
      </p>

      <h4>采购状态怎么来的</h4>
      <p>
        系统拿「采购记录里买到的数量合计」去比「物料总量」：一分没买是未买，
        买了一些是部分已买，买够了或买超了是已买完。
      </p>
      <p>
        定金单独算一态「已付定」：定金是钱先付了、货还没到，它只算已付金额、
        不推进已买数量，所以状态停在「已付定」，同时把那两个「未付」里的钱扣掉。
        货到了再记一笔尾款（不勾定金）就结清了。
      </p>
      <p class="warn">
        只填金额不填数量的话，状态不会往前动 —— 记的时候记得把数量写上
        （定金除外：定金本来就不看数量）。
      </p>

      <h4>日常怎么用</h4>
      <ol>
        <li>先去「设置 / 数据」把分组（房间）和分类建好，两份清单可以各建一套。</li>
        <li>「清单」页右下角新增物料，把总量、单价填上；要分房间买的就在分配里写清。</li>
        <li>买完就点那条物料的「采购状态」标签，记一笔：数量、金额、日期、商家。</li>
        <li>逛「总览」看进度和余额，「矩阵」页按房间核对各买多少。</li>
      </ol>

      <h4>数据安全与找回</h4>
      <ul>
        <li>删物料是软删，先去「回收站」，能恢复（分配和采购记录一起回来）。</li>
        <li>
          每次改动都会记进「设置 / 数据 → 日志」，写清改了什么；
          弄错了可以一键回退最近一次操作，连着点是往上一条条撤。
        </li>
        <li>「备份」页可以导出当前清单为表格，或者下载整库文件；换机器时整库搬过去就行。</li>
      </ul>

      <h4>手机上用</h4>
      <p>
        单机版 App 不连服务器也能记账；想和电脑互通，就在「设置 → 服务器」里
        填上这套服务的地址和账号，之后两边改动会自动对齐。
      </p>
    </div>
  </el-dialog>
</template>

<style scoped>
/* 说明文字较长，给个可滚动的正文区，头尾固定 */
.help {
  max-height: 68vh;
  overflow-y: auto;
  padding-right: 6px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--ios-label);
}
.lead { margin: 0 0 18px; color: var(--ios-label-2); }
.help h4 {
  margin: 20px 0 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--ios-label);
}
.help h4:first-child { margin-top: 0; }
.help ul, .help ol { margin: 0; padding-left: 20px; }
.help li { margin-bottom: 6px; }
.help dl { margin: 0; }
.help dt { font-weight: 600; margin-top: 10px; }
.help dd { margin: 2px 0 0; color: var(--ios-label-2); }
.help p { margin: 8px 0; }
.warn { color: var(--ios-orange); }
</style>
