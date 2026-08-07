/** Canvas 运行时的轻量文案表；玩法状态和绘制代码不直接判断语言。 */
export interface GameStrings {
  queueCount: string
  noParkingSpace: string
  adNotCompleted: string
  toolNotNeeded: string
  adUnavailable: string
  noRemovableCar: string
  noQueueOptimization: string
  refresh: string
  remove: string
  sort: string
  winTitle: string
  winMessage: string
  nextLevel: string
  home: string
  fullTitle: string
  fullMessage: string
  unlockToContinue: string
  retry: string
  gameTitle: string
  gameSubtitle: string
  startGame: string
  selectLevel: string
  level: (levelNumber: number) => string
}

const zhCn: GameStrings = {
  queueCount: '候车人数',
  noParkingSpace: '没有空车位！',
  adNotCompleted: '广告未完成，未使用道具',
  toolNotNeeded: '当前状态无需使用该道具',
  adUnavailable: '暂无可用广告，请稍后再试',
  noRemovableCar: '没有可消除的车',
  noQueueOptimization: '暂时没有可优化的排队',
  refresh: '刷新',
  remove: '消除',
  sort: '排序',
  winTitle: '🎉 过关！',
  winMessage: '所有乘客已安全离场',
  nextLevel: '下一关 →',
  home: '返回首页',
  fullTitle: '😵 车位满啦！',
  fullMessage: '队首乘客没有能上的车了',
  unlockToContinue: 'AD  解锁车位继续',
  retry: '⟳ 再试一次',
  gameTitle: '车水马龙',
  gameSubtitle: '点击车辆 · 接走乘客 · 疏通车阵',
  startGame: '开始游戏',
  selectLevel: '选择关卡',
  level: (levelNumber) => `第 ${levelNumber} 关`,
}

const en: GameStrings = {
  queueCount: 'WAITING',
  noParkingSpace: 'No parking space!',
  adNotCompleted: 'Ad not completed. Tool not used.',
  toolNotNeeded: 'This tool is not needed right now.',
  adUnavailable: 'No ad is available. Please try again later.',
  noRemovableCar: 'No car can be removed.',
  noQueueOptimization: 'The queue cannot be improved right now.',
  refresh: 'Reveal',
  remove: 'Remove',
  sort: 'Sort',
  winTitle: '🎉 Cleared!',
  winMessage: 'Every passenger left safely',
  nextLevel: 'Next Level →',
  home: 'Home',
  fullTitle: '😵 Parking Full!',
  fullMessage: 'The first passenger cannot board any car',
  unlockToContinue: 'AD  Unlock Space',
  retry: '⟳ Try Again',
  gameTitle: 'Parking Flow',
  gameSubtitle: 'Tap cars · Pick up passengers · Clear the lot',
  startGame: 'Start Game',
  selectLevel: 'Select Level',
  level: (levelNumber) => `Level ${levelNumber}`,
}

const ja: GameStrings = {
  queueCount: '待機中',
  noParkingSpace: '空きスペースがありません！',
  adNotCompleted: '広告が完了していないため、アイテムは使われませんでした。',
  toolNotNeeded: '今はこのアイテムを使う必要はありません。',
  adUnavailable: '利用できる広告がありません。後でもう一度お試しください。',
  noRemovableCar: '消去できる車がありません。',
  noQueueOptimization: '今は待機列を改善できません。',
  refresh: '表示',
  remove: '消去',
  sort: '並べ替え',
  winTitle: '🎉 クリア！',
  winMessage: '全員が安全に出発しました',
  nextLevel: '次のレベル →',
  home: 'ホーム',
  fullTitle: '😵 駐車場が満車です！',
  fullMessage: '先頭の乗客がどの車にも乗れません',
  unlockToContinue: 'AD  スペースを解放',
  retry: '⟳ もう一度',
  gameTitle: 'パーキングフロー',
  gameSubtitle: '車をタップ · 乗客を乗せる · 駐車場を整理',
  startGame: 'ゲーム開始',
  selectLevel: 'レベル選択',
  level: (levelNumber) => `レベル ${levelNumber}`,
}

const ko: GameStrings = {
  queueCount: '대기 인원',
  noParkingSpace: '빈 주차 공간이 없습니다!',
  adNotCompleted: '광고가 완료되지 않아 아이템을 사용하지 않았습니다.',
  toolNotNeeded: '지금은 이 아이템이 필요하지 않습니다.',
  adUnavailable: '사용 가능한 광고가 없습니다. 잠시 후 다시 시도하세요.',
  noRemovableCar: '제거할 수 있는 차량이 없습니다.',
  noQueueOptimization: '지금은 대기열을 개선할 수 없습니다.',
  refresh: '공개',
  remove: '제거',
  sort: '정렬',
  winTitle: '🎉 클리어!',
  winMessage: '모든 승객이 안전하게 떠났습니다',
  nextLevel: '다음 레벨 →',
  home: '홈',
  fullTitle: '😵 주차장이 가득 찼어요!',
  fullMessage: '첫 승객이 탑승할 차량이 없습니다',
  unlockToContinue: 'AD  공간 잠금 해제',
  retry: '⟳ 다시 시도',
  gameTitle: '주차 흐름',
  gameSubtitle: '차량 탭 · 승객 태우기 · 주차장 정리',
  startGame: '게임 시작',
  selectLevel: '레벨 선택',
  level: (levelNumber) => `레벨 ${levelNumber}`,
}

const es: GameStrings = {
  queueCount: 'EN ESPERA',
  noParkingSpace: '¡No hay espacio libre!',
  adNotCompleted: 'El anuncio no se completó. No se usó el objeto.',
  toolNotNeeded: 'Este objeto no es necesario ahora.',
  adUnavailable: 'No hay anuncios disponibles. Inténtalo más tarde.',
  noRemovableCar: 'No se puede retirar ningún coche.',
  noQueueOptimization: 'La cola no se puede mejorar ahora.',
  refresh: 'Revelar',
  remove: 'Retirar',
  sort: 'Ordenar',
  winTitle: '🎉 ¡Completado!',
  winMessage: 'Todos los pasajeros salieron a salvo',
  nextLevel: 'Siguiente nivel →',
  home: 'Inicio',
  fullTitle: '😵 ¡Aparcamiento lleno!',
  fullMessage: 'El primer pasajero no puede subir a ningún coche',
  unlockToContinue: 'AD  Desbloquear espacio',
  retry: '⟳ Reintentar',
  gameTitle: 'Flujo de aparcamiento',
  gameSubtitle: 'Toca coches · Recoge pasajeros · Despeja la zona',
  startGame: 'Empezar',
  selectLevel: 'Elegir nivel',
  level: (levelNumber) => `Nivel ${levelNumber}`,
}

const ptBr: GameStrings = {
  queueCount: 'AGUARDANDO',
  noParkingSpace: 'Não há vaga livre!',
  adNotCompleted: 'O anúncio não foi concluído. O item não foi usado.',
  toolNotNeeded: 'Este item não é necessário agora.',
  adUnavailable: 'Nenhum anúncio disponível. Tente novamente mais tarde.',
  noRemovableCar: 'Nenhum carro pode ser removido.',
  noQueueOptimization: 'A fila não pode ser melhorada agora.',
  refresh: 'Revelar',
  remove: 'Remover',
  sort: 'Ordenar',
  winTitle: '🎉 Concluído!',
  winMessage: 'Todos os passageiros saíram em segurança',
  nextLevel: 'Próxima fase →',
  home: 'Início',
  fullTitle: '😵 Estacionamento lotado!',
  fullMessage: 'O primeiro passageiro não consegue entrar em nenhum carro',
  unlockToContinue: 'AD  Liberar vaga',
  retry: '⟳ Tentar novamente',
  gameTitle: 'Fluxo do estacionamento',
  gameSubtitle: 'Toque nos carros · Leve passageiros · Libere a área',
  startGame: 'Começar',
  selectLevel: 'Selecionar fase',
  level: (levelNumber) => `Fase ${levelNumber}`,
}

const fr: GameStrings = {
  queueCount: 'EN ATTENTE',
  noParkingSpace: 'Aucune place disponible !',
  adNotCompleted: 'La publicité n’est pas terminée. L’objet n’a pas été utilisé.',
  toolNotNeeded: 'Cet objet n’est pas utile pour le moment.',
  adUnavailable: 'Aucune publicité disponible. Réessayez plus tard.',
  noRemovableCar: 'Aucune voiture ne peut être retirée.',
  noQueueOptimization: 'La file ne peut pas être améliorée pour le moment.',
  refresh: 'Révéler',
  remove: 'Retirer',
  sort: 'Trier',
  winTitle: '🎉 Terminé !',
  winMessage: 'Tous les passagers sont partis en sécurité',
  nextLevel: 'Niveau suivant →',
  home: 'Accueil',
  fullTitle: '😵 Parking complet !',
  fullMessage: 'Le premier passager ne peut monter dans aucune voiture',
  unlockToContinue: 'AD  Débloquer une place',
  retry: '⟳ Réessayer',
  gameTitle: 'Flux du parking',
  gameSubtitle: 'Touchez les voitures · Prenez les passagers · Libérez la zone',
  startGame: 'Commencer',
  selectLevel: 'Choisir un niveau',
  level: (levelNumber) => `Niveau ${levelNumber}`,
}

const de: GameStrings = {
  queueCount: 'WARTEND',
  noParkingSpace: 'Kein Parkplatz frei!',
  adNotCompleted: 'Werbung nicht abgeschlossen. Gegenstand wurde nicht verwendet.',
  toolNotNeeded: 'Dieser Gegenstand wird gerade nicht benötigt.',
  adUnavailable: 'Keine Werbung verfügbar. Bitte später erneut versuchen.',
  noRemovableCar: 'Kein Fahrzeug kann entfernt werden.',
  noQueueOptimization: 'Die Warteschlange kann gerade nicht verbessert werden.',
  refresh: 'Aufdecken',
  remove: 'Entfernen',
  sort: 'Sortieren',
  winTitle: '🎉 Geschafft!',
  winMessage: 'Alle Fahrgäste sind sicher abgefahren',
  nextLevel: 'Nächstes Level →',
  home: 'Start',
  fullTitle: '😵 Parkplatz voll!',
  fullMessage: 'Der erste Fahrgast kann in kein Fahrzeug einsteigen',
  unlockToContinue: 'AD  Platz freischalten',
  retry: '⟳ Erneut versuchen',
  gameTitle: 'Parkplatzfluss',
  gameSubtitle: 'Autos antippen · Fahrgäste aufnehmen · Parkplatz räumen',
  startGame: 'Spiel starten',
  selectLevel: 'Level auswählen',
  level: (levelNumber) => `Level ${levelNumber}`,
}

const requestedLanguage = new URLSearchParams(location.search).get('lang') || navigator.language
const normalizedLanguage = requestedLanguage.toLowerCase()

export const gameStrings = (() => {
  if (normalizedLanguage.startsWith('zh')) return zhCn
  if (normalizedLanguage.startsWith('ja')) return ja
  if (normalizedLanguage.startsWith('ko')) return ko
  if (normalizedLanguage.startsWith('es')) return es
  if (normalizedLanguage.startsWith('pt')) return ptBr
  if (normalizedLanguage.startsWith('fr')) return fr
  if (normalizedLanguage.startsWith('de')) return de
  return en
})()
