const labels = {
  sensor_count:"센서 데이터", gps_count:"GPS 위치", image_count:"원본 이미지",
  bbox_count:"BBOX 탐지", segmentation_count:"균열 탐지",
  hazard_evidence_count:"위험 근거", accident_count:"사고 이벤트"
};
const units = {sensor_count:"건",gps_count:"건",image_count:"장",bbox_count:"건",segmentation_count:"건",hazard_evidence_count:"건",accident_count:"건"};

function healthCard(label,value){return `<div class="health-card"><i></i><div><small>${label}</small><strong>${value}</strong></div></div>`}
function safe(value){const div=document.createElement("div");div.textContent=value??"";return div.innerHTML}
function formatEventMillis(value){
  const millis=Number(value);
  if(!Number.isFinite(millis))return "-";
  const date=new Date(millis);
  const base=date.toLocaleTimeString("ko-KR",{hour12:false,hour:"2-digit",minute:"2-digit",second:"2-digit"});
  return `${base}.${String(date.getMilliseconds()).padStart(3,"0")}`;
}
function peakDetail(item){
  if(item.category_code!=="SENSOR_PEAK")return "";
  const detail=item.details||{};
  if(detail.peak_sample_at==null)return '<small class="peak-detail">Peak 센서 샘플 없음</small>';
  return `<small class="peak-detail">촬영 ${safe(formatEventMillis(detail.captured_at))} · Peak ${safe(formatEventMillis(detail.peak_sample_at))} · 가속도 X/Y/Z ${safe(detail.accel_x)}/${safe(detail.accel_y)}/${safe(detail.accel_z)} m/s² · 크기 ${safe(detail.acceleration_magnitude_mps2)} m/s²</small>`;
}
let lastEventId=0;
let accumulatedEventCount=0;
const defaultPipeline=["앱 수집","서버 전송","BBOX 추론","동일 원본 SEG 추론","DB 저장","후보 분류"];

function renderPipeline(items){
  const stages=(items&&items.length?items:defaultPipeline.map((label,index)=>({label,state:index===0?"active":"pending"})));
  document.querySelector("#pipeline").innerHTML=stages.map((item,index)=>
    `${index?'<i>→</i>':''}<div class="step ${safe(item.state)}"><b>${index+1}</b><span>${safe(item.label)}</span><small>${item.state==="done"?"완료":item.state==="active"?"처리 중":item.state==="skipped"?"대상 없음":"대기"}</small></div>`
  ).join("");
}

function renderStatus(data){
  document.querySelector("#health").innerHTML =
    healthCard("서버",data.server)+healthCard("데이터베이스",data.database)+
    healthCard("이미지 저장소",data.storage)+healthCard("BBOX 모델",data.bbox_model)+
    healthCard("Segmentation 모델",data.segmentation_model);
  const ride=data.current_ride;
  const stats=document.querySelector("#stats");
  if(!ride){document.querySelector("#ride-id").textContent="주행 대기";stats.innerHTML='<div class="empty">저장된 최근 주행을 기다리고 있습니다.</div>';renderPipeline(null);return;}
  document.querySelector("#ride-id").textContent=`주행 ${ride.ride_session_id.slice(0,8)}…`;
  stats.innerHTML=Object.keys(labels).map(key=>`<div class="stat"><span>${labels[key]}</span><strong>${ride[key]??0}${units[key]}</strong></div>`).join("")+
    `<div class="stat sync"><span>동기화 상태</span><strong>${safe(ride.sync_status)}</strong></div>`;
  renderPipeline(ride.pipeline);
}

function appendEvents(items){
  if(!items.length)return;
  const root=document.querySelector("#events");
  root.querySelector(".empty")?.remove();
  root.insertAdjacentHTML("beforeend",items.map(item=>`<div class="event ${item.category_code==="SENSOR_PEAK"?"peak-event":""}"><time>${safe(item.time)}</time><span class="badge">${safe(item.category)}</span><div><p>${safe(item.message)}</p>${peakDetail(item)}<small class="account">${safe(item.account)}</small></div><span class="result">${safe(item.status)}</span></div>`).join(""));
  lastEventId=Math.max(lastEventId,...items.map(item=>Number(item.id)||0));
  accumulatedEventCount+=items.length;
  document.querySelector("#event-count").textContent=`누적 ${accumulatedEventCount.toLocaleString("ko-KR")}건`;
  root.scrollTop=root.scrollHeight;
}

async function refresh(){
  try{
    const [statusRes,eventRes]=await Promise.all([fetch("/api/demo-monitor/status",{cache:"no-store"}),fetch(`/api/demo-monitor/events?limit=500&after_id=${lastEventId}`,{cache:"no-store"})]);
    if(!statusRes.ok||!eventRes.ok) throw new Error("monitor request failed");
    renderStatus(await statusRes.json());appendEvents((await eventRes.json()).items);
    document.querySelector("#updated-at").textContent=`마지막 갱신 ${new Date().toLocaleTimeString("ko-KR")}`;
  }catch(error){document.querySelector("#updated-at").textContent="상태를 다시 확인하고 있습니다.";}
}
setInterval(()=>document.querySelector("#clock").textContent=new Date().toLocaleTimeString("ko-KR",{hour12:false}),1000);
renderPipeline(null);
refresh();setInterval(refresh,2000);
