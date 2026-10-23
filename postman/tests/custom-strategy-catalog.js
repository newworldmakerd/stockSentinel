pm.test('management catalog is available', () => pm.response.to.have.status(200));
const catalog = pm.response.json();
pm.test('catalog exposes the 17 supported factors', () => {
  pm.expect(catalog.factors).to.have.length(17);
  pm.expect(catalog.factors.map(f => f.code)).to.include.members(['CLOSE', 'MA20', 'DIF', 'DEA', 'VOLUME_RATIO', 'CHIP90']);
});
pm.test('compound and crossover rules remain bounded', () => {
  pm.expect(catalog.maxConditions).to.equal(20);
  pm.expect(catalog.maxGroupDepth).to.equal(2);
  pm.expect(catalog.maxSymbols).to.equal(100);
  pm.expect(catalog.operators).to.include.members(['CROSS_UP','CROSS_DOWN','RISING','FALLING']);
});
pm.test('unimplemented factors are not advertised as executable', () => {
  pm.expect(catalog.factors.map(f => f.code)).not.to.include('DAILY_RSI14');
  pm.expect(catalog.factors.find(f => f.code === 'CHIP90').requirement).to.include('落库');
});
